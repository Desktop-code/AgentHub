package com.agenthub.workflow.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.workflow.api.dto.WorkflowRunDetailResp;
import com.agenthub.workflow.api.dto.WorkflowRunSummaryResp;
import com.agenthub.workflow.api.dto.WorkflowRunTrialReq;
import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDraft;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.model.WorkflowScopeView;
import com.agenthub.workflow.domain.model.WorkflowWorkbenchOutcome;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowDraftRepository;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowFingerprint;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.agenthub.workflow.domain.service.WorkflowStateMachine;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流试跑 / 运行详情 / 取消 用例。所属域：workflow 的 application。
 * <p>边界：调试可以按当前已保存草稿跑（{@code useDraft}），不写 release、不改默认指针；
 * 显式 releaseId 或默认指针的入口仍只跑已发布快照。工作台「运行」只认默认发布指针。
 * AGENT / TOOL 出站交给引擎端口；控制台入口只产 TRIAL；应用入站（{@code trigger_type=APP}）走
 * {@code WorkflowAppInvokeAppService}，同样绑 release 且共用本引擎。
 * <p>为什么 createTrial 不是事务：引擎内会调模型 / 工具（秒级到分钟级），
 * 长事务会占连接并放大锁；改为「建 run + 预建节点行」短事务后同步调度，
 * 调度期每一步自己落状态，失败也能从库表追溯。
 */
@Service
public class WorkflowRunAppService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunAppService.class);

    /** 用户回复列宽：与 workflow_node_run.reply_text 的 varchar(2000) 对齐，写入前截断 */
    private static final int REPLY_MAX = 2000;

    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowHumanTaskRepository humanTasks;
    private final WorkflowReleaseRepository releases;
    private final WorkflowDefRepository defs;
    private final WorkflowTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final WorkflowGraphCodec codec;
    private final WorkflowEngine engine;
    private final WorkflowRunAssembler assembler;
    private final WorkflowHumanTaskAppService humanTaskService;
    private final OperLogWriter operLogWriter;
    private final WorkflowProperties properties;
    private final ObjectMapper json;
    /** 埋点端口：取消这类不经过引擎的终态迁移，需要自己收口根 trace */
    private final WorkflowTraceWriter traces;
    /** 试跑落库后的后台调度；工作台路径不经过它 */
    private final WorkflowTrialDispatcher trialDispatcher;
    /** 当前草稿；只在 useDraft 调试时读取，不在这里发布 */
    private final WorkflowDraftRepository drafts;
    /** 与发布同一套图检查；草稿调试在建 run 前拦住未通过的图 */
    private final WorkflowPublishCheck publishCheck;

    /**
     * 子图范围还原：只用于「复用进行中的 run」时补报已发生的子图框。
     * 这是展示旁路，单测可不起 Spring 容器；缺失时退化为只报父图节点。
     */
    private WorkflowScopeTreeBuilder scopeTreeBuilder;

    /**
     * @param runs             run 仓储
     * @param nodeRuns         node_run 仓储
     * @param humanTasks       人工待办仓储（取消时收口 OPEN 待办）
     * @param releases         发布版本仓储
     * @param defs             定义仓储（解析默认发布指针）
     * @param tenantProvider   当前租户
     * @param operatorContext  当前操作人
     * @param codec            图 JSON 解析
     * @param engine           调度引擎
     * @param assembler        详情组装
     * @param humanTaskService 懒超时处理（get 时顺带结算到期待办）
     * @param operLogWriter    操作日志（只记 run 元数据）
     * @param properties       硬顶配置（max-node-runs-per-run）
     * @param json             全站 ObjectMapper
     * @param traces           工作流埋点（取消时收口根 trace）
     * @param trialDispatcher 试跑后台调度（只给 {@link #trial} 用）
     * @param drafts          草稿仓储（按当前草稿调试时读取）
     * @param publishCheck    发布口径检查（草稿调试开跑前）
     */
    public WorkflowRunAppService(WorkflowRunRepository runs,
                                 WorkflowNodeRunRepository nodeRuns,
                                 WorkflowHumanTaskRepository humanTasks,
                                 WorkflowReleaseRepository releases,
                                 WorkflowDefRepository defs,
                                 WorkflowTenantProvider tenantProvider,
                                 OperatorContext operatorContext,
                                 WorkflowGraphCodec codec,
                                 WorkflowEngine engine,
                                 WorkflowRunAssembler assembler,
                                 WorkflowHumanTaskAppService humanTaskService,
                                 OperLogWriter operLogWriter,
                                 WorkflowProperties properties,
                                 ObjectMapper json,
                                 WorkflowTraceWriter traces,
                                 WorkflowTrialDispatcher trialDispatcher,
                                 WorkflowDraftRepository drafts,
                                 WorkflowPublishCheck publishCheck) {
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.humanTasks = humanTasks;
        this.releases = releases;
        this.defs = defs;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.codec = codec;
        this.engine = engine;
        this.assembler = assembler;
        this.humanTaskService = humanTaskService;
        this.operLogWriter = operLogWriter;
        this.properties = properties;
        this.json = json;
        this.traces = traces;
        this.trialDispatcher = trialDispatcher;
        this.drafts = drafts;
        this.publishCheck = publishCheck;
    }

    /**
     * 注入子图范围还原器（展示旁路，可选）。
     *
     * <p>用 setter 而不是构造参数：单测直接 new 本类时不关心展示补报，
     * 缺失时只是复用分支少报子图框，不影响调度 / 权限 / 落库。
     *
     * @param scopeTreeBuilder 子图范围还原器
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setScopeTreeBuilder(WorkflowScopeTreeBuilder scopeTreeBuilder) {
        this.scopeTreeBuilder = scopeTreeBuilder;
    }

    /**
     * 发起一次试跑。run 与节点行落库后立即返回，引擎在后台继续。
     *
     * <p>返回时状态通常还是 CREATED（调度线程尚未把条件更新写成 RUNNING）。
     * 调用方用 runId 轮询详情。触发类型仍是 TRIAL，不写工作台会话。
     *
     * @param req useDraft，或 releaseId / workflowId+默认发布指针，以及输入样例
     * @return 刚落库的 run 详情（含进行中的节点行）
     * @throws BizException 未发布 / 版本不存在 / 草稿校验未通过 / 输入不是对象 / 图不可执行
     */
    public WorkflowRunDetail trial(WorkflowRunTrialReq req) {
        long tenantId = tenantProvider.currentTenantId();
        if (req != null && Boolean.TRUE.equals(req.getUseDraft())) {
            return trialCurrentDraft(req, tenantId);
        }
        WorkflowRelease release = resolveRelease(req, tenantId);
        WorkflowDef def = requireDef(release.getWorkflowId(), tenantId);

        JsonNode input = req.getInput();
        if (input == null || input.isNull()) {
            input = json.createObjectNode();
        }
        if (!input.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "试跑输入必须是 JSON 对象，例如 {\"query\":\"...\"}");
        }
        // 建 run 前先确认图可执行，避免留下「已创建但永远动不了」的脏实例
        WorkflowGraph graph = codec.parse(release.getGraphJson());
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        // 硬顶：一次 run 预建的 node_run 条数；超了直接拒绝建实例，避免半截数据
        int nodeRunsCap = properties.effectiveMaxNodeRunsPerRun();
        if (runtime.nodes().size() > nodeRunsCap) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该图有 " + runtime.nodes().size() + " 个节点，超过单次运行上限 " + nodeRunsCap
                            + "（agenthub.workflow.max-node-runs-per-run）；请拆分工作流");
        }

        Long operatorId = operatorContext.requireUserId();
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(tenantId);
        run.setWorkflowId(release.getWorkflowId());
        run.setReleaseId(release.getId());
        run.setReleaseVersion(release.getVersion());
        run.setFingerprint(release.getFingerprint());
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(operatorId);
        run.setInputJson(input.toString());
        // 发布快照原文拷贝进 run：release 表后续再发布也不影响这个实例
        run.setGraphJson(release.getGraphJson());
        run.setStartTime(LocalDateTime.now());
        run.setCreatedBy(operatorId);
        Long runId = runs.insert(run);
        run.setId(runId);

        nodeRuns.insertBatch(pendingRows(runId, runtime));

        operLogWriter.write("workflow", "run_trial", "workflow_run", String.valueOf(runId),
                "试跑工作流 code=" + def.getCode() + " release=" + release.getId());
        // 先返回 runId：引擎改到后台，租户和操作人在提交时捕获
        trialDispatcher.submit(runId, operatorId, tenantId);
        return detail(runId, tenantId);
    }

    /**
     * 按当前已保存草稿发起一次调试。
     * <p>调用方应先把画布写入草稿。这里只拷贝那份图到 run，不插入 workflow_release，
     * 也不改 default_release_id。release_id / release_version 留空，记录里标「未发布」。
     * 图未通过发布口径检查时直接拒绝，不留下半截 run。
     *
     * @param req      必须带 workflowId；input 须是 JSON 对象
     * @param tenantId 当前租户
     * @return 刚落库的 run 详情
     * @throws BizException 定义不存在、草稿缺失、校验未通过或图不可执行
     */
    private WorkflowRunDetail trialCurrentDraft(WorkflowRunTrialReq req, long tenantId) {
        if (req.getWorkflowId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "按当前草稿调试需要 workflowId");
        }
        WorkflowDef def = requireDef(req.getWorkflowId(), tenantId);
        WorkflowDraft draft = drafts.findByWorkflowId(def.getId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流草稿不存在"));
        JsonNode input = req.getInput();
        if (input == null || input.isNull()) {
            input = json.createObjectNode();
        }
        if (!input.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "试跑输入必须是 JSON 对象，例如 {\"query\":\"...\"}");
        }
        WorkflowGraph graph = codec.parse(draft.getGraphJson());
        // 与发布同一套检查：没通过就不建 run，避免用坏图开跑；子图链路一并校验
        publishCheck.requirePublishable(def.getId(), def.getTenantId(), graph);
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        int nodeRunsCap = properties.effectiveMaxNodeRunsPerRun();
        if (runtime.nodes().size() > nodeRunsCap) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该图有 " + runtime.nodes().size() + " 个节点，超过单次运行上限 " + nodeRunsCap
                            + "（agenthub.workflow.max-node-runs-per-run）；请拆分工作流");
        }
        Long operatorId = operatorContext.requireUserId();
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(tenantId);
        run.setWorkflowId(def.getId());
        // 显式空：没有发布行可指，不能造一条假 release 去填外键
        run.setReleaseId(null);
        run.setReleaseVersion(null);
        // 另外记下「这次调试当时的默认发布版本」：记录列表要显示 vN + 未发布，
        // 又不能用此刻的 default_release_id 反推（之后发布新版会让旧记录跟着变）
        run.setDraftBaseReleaseVersion(defaultReleaseVersion(def));
        run.setFingerprint(WorkflowFingerprint.of(graph));
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(operatorId);
        run.setInputJson(input.toString());
        run.setGraphJson(draft.getGraphJson());
        run.setStartTime(LocalDateTime.now());
        run.setCreatedBy(operatorId);
        Long runId = runs.insert(run);
        run.setId(runId);
        nodeRuns.insertBatch(pendingRows(runId, runtime));
        operLogWriter.write("workflow", "run_trial", "workflow_run", String.valueOf(runId),
                "调试工作流 code=" + def.getCode() + " 按当前草稿");
        trialDispatcher.submit(runId, operatorId, tenantId);
        return detail(runId, tenantId);
    }

    /**
     * 工作台触发一次工作流（刀5B）：只绑默认发布指针，结果由调用方投影回同一条会话。
     *
     * <p>为什么单独一个入口而不复用 {@link #trial(WorkflowRunTrialReq)}：
     * <ul>
     *   <li>试跑可以指定任意 release，工作台<b>只认默认发布指针</b>（禁跑草稿）；</li>
     *   <li>试跑不记会话，工作台要写 {@code workbench_session_id} 才能做防重与人工恢复追写；</li>
     *   <li>触发类型不同（WORKBENCH vs TRIAL），观测与操作日志口径要能区分。</li>
     * </ul>
     *
     * <p>防重：同一会话 + 同一工作流还有 RUNNING / PAUSED_HUMAN 时，不新建 run，直接返回那条；
     * 调用方据此给出「仍在进行 / 等待人工处理」，<b>不</b>自动取消旧 run。
     *
     * <p>入参：{@code {"query": "<本轮用户消息>"}}，与定稿的 {@code $.input.query} 对齐。
     *
     * <p>执行：同步推进到终态或 PAUSED_HUMAN（与试跑同一套引擎）。工作流引擎与
     * Agent/MA 对话栈保持分离，这里只负责建 run 与调用引擎。
     *
     * @param workflowId 工作流定义 id
     * @param query      本轮用户消息；会作为 {@code input.query}
     * @param sessionId  工作台会话 id
     * @param userId     平台用户（工作台会话归属人）
     * @return 新 run 或复用的未结束 run
     * @throws BizException 定义不存在 / 未发布 / 无默认发布 / 图不可执行 / 入参非法
     */
    public WorkflowWorkbenchOutcome runFromWorkbench(Long workflowId, String query, long sessionId, long userId) {
        return runFromWorkbench(workflowId, query, sessionId, userId, null);
    }

    /**
     * 工作台触发工作流（带节点观察者）。
     *
     * <p>观察者只用于「工作台消息流里的执行卡片」：复用未结束 run 时也要先把已有节点状态
     * 报一次，否则卡片会是一张没有步骤的空壳；新建 run 时交给引擎在调度开始后回调。
     *
     * @param workflowId 工作流定义 id
     * @param query      本轮用户消息；会作为 {@code input.query}
     * @param sessionId  工作台会话 id
     * @param userId     平台用户（工作台会话归属人）
     * @param listener   节点状态观察者；可空
     * @return 新 run 或复用的未结束 run
     * @throws BizException 定义不存在 / 未发布 / 无默认发布 / 图不可执行 / 入参非法
     */
    public WorkflowWorkbenchOutcome runFromWorkbench(Long workflowId, String query, long sessionId, long userId,
                                                     WorkflowRunListener listener) {
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工作流入参不能为空");
        }
        long tenantId = tenantProvider.currentTenantId();
        WorkflowDef def = requireDef(workflowId, tenantId);
        WorkflowRelease release = requireDefaultRelease(def);

        // 先查未结束 run：命中就不建第二条，也不消耗一次调度
        Optional<WorkflowRun> active = runs.findActiveByWorkbenchSession(workflowId, sessionId);
        if (active.isPresent()) {
            WorkflowRun existing = active.get();
            if (WorkflowRunStatus.parse(existing.getStatus()) == WorkflowRunStatus.PAUSED_HUMAN) {
                // 顺带做一次懒超时结算：到期策略可能已经把它推到终态，用户这轮就能看到结果
                humanTaskService.resolveExpired(existing);
                existing = runs.findById(existing.getId()).orElse(existing);
            }            // 复用没有经过引擎：把库里已有的节点状态先报一次，卡片才不会是一张没有步骤的空壳
            publishExisting(existing, listener);
            return new WorkflowWorkbenchOutcome(existing, true);
        }

        // 建 run 前先确认图可执行，避免留下「已创建但永远动不了」的脏实例
        WorkflowGraph graph = codec.parse(release.getGraphJson());
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        int nodeRunsCap = properties.effectiveMaxNodeRunsPerRun();
        if (runtime.nodes().size() > nodeRunsCap) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该图有 " + runtime.nodes().size() + " 个节点，超过单次运行上限 " + nodeRunsCap
                            + "（agenthub.workflow.max-node-runs-per-run）；请拆分工作流");
        }

        ObjectNode input = json.createObjectNode();
        input.put("query", query.trim());
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(tenantId);
        run.setWorkflowId(def.getId());
        run.setReleaseId(release.getId());
        run.setReleaseVersion(release.getVersion());
        run.setFingerprint(release.getFingerprint());
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.WORKBENCH.name());
        run.setTriggeredBy(userId);
        run.setWorkbenchSessionId(sessionId);
        run.setInputJson(input.toString());
        // 发布快照原文拷贝进 run：之后再发布不影响本实例
        run.setGraphJson(release.getGraphJson());
        run.setStartTime(LocalDateTime.now());
        run.setCreatedBy(userId);
        Long runId = runs.insert(run);
        run.setId(runId);
        nodeRuns.insertBatch(pendingRows(runId, runtime));

        operLogWriter.write("workflow", "run_workbench", "workflow_run", String.valueOf(runId),
                "工作台运行工作流 code=" + def.getCode() + " release=" + release.getId()
                        + " session=" + sessionId);
        executeSafely(run, userId, listener);
        WorkflowRun fresh = runs.findById(runId).orElse(run);
        return new WorkflowWorkbenchOutcome(fresh, false);
    }

    /**
     * 把已存在 run 的节点状态报给观察者（复用分支专用）。
     *
     * <p>图或节点行读不出来时只跳过：这是一张展示用卡片，不能因为不可视化就拒绝一次防重命中。
     *
     * @param run     已存在的 run
     * @param listener 观察者；可空
     */
    private void publishExisting(WorkflowRun run, WorkflowRunListener listener) {
        if (listener == null || run == null || run.getId() == null) {
            return;
        }
        try {
            WorkflowGraph graph = codec.parse(run.getGraphJson());
            WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
            List<WorkflowNodeRun> rows = nodeRuns.listByRunId(run.getId());
            /*
             * 复用进行中的 run：子图节点行已经在库里，按同一套范围还原先报一次框，
             * 否则防重命中拿到的卡片只有父图节点，子图进度要等下一次节点变更才出现。
             * 根节点也用还原后的展示名（SUBGRAPH 换成「被调工作流名 · vN」），与直播一致。
             */
            WorkflowScopeTree tree = scopeTreeBuilder == null
                    ? null : scopeTreeBuilder.build(run, graph, rows);
            listener.runStarted(tree == null ? runtime.nodes() : tree.rootNodes(), rows,
                    tree == null ? runtime.outgoingIndex() : tree.rootOutgoing());
            if (tree != null) {
                for (WorkflowScopeView scope : tree.childViews()) {
                    listener.scopeStarted(scope);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("复用工作流 run 时读取节点状态失败 runId={} type={}",
                    run.getId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * 工作台入口的引擎调用：意外异常时把 run 收成 FAILED，避免留下永久 RUNNING 的实例。
     *
     * <p>正常失败（节点失败、墙钟、撞限）由引擎自己落终态；这里只兜「引擎本身抛异常」
     * 这种极小概率情况。收口失败（并发已被别人改写）只记日志，以库里的状态为准。
     *
     * @param run        已落库的 run
     * @param userId     操作人（会话归属人）
     */
    private void executeSafely(WorkflowRun run, long userId) {
        executeSafely(run, userId, null);
    }

    /**
     * 工作台入口的引擎调用（带节点观察者）。
     *
     * @param run      已落库的 run
     * @param userId   操作人（会话归属人）
     * @param listener 节点状态观察者；可空
     */
    private void executeSafely(WorkflowRun run, long userId, WorkflowRunListener listener) {
        try {
            engine.execute(run, userId, listener);
        } catch (RuntimeException ex) {
            log.warn("工作台工作流执行异常 runId={} type={}", run.getId(), ex.getClass().getSimpleName(), ex);
            WorkflowRunStatus current = WorkflowRunStatus.parse(run.getStatus());
            if (current != null && !current.terminal()) {
                runs.updateStatusIfMatches(run.getId(), current.name(), WorkflowRunStatus.FAILED.name(),
                        UserFacingText.safeMessage(ex, "工作流运行失败"), null, LocalDateTime.now(), userId);
            }
        }
    }

    /**
     * 工作台只跑默认发布指针：未发布 / 指针缺失都明确拒绝，绝不回落到草稿。
     *
     * @param def 已校验归属的定义
     * @return 默认发布版本
     * @throws BizException 尚未发布，或默认发布记录缺失
     */
    private WorkflowRelease requireDefaultRelease(WorkflowDef def) {
        if (def.getDefaultReleaseId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "工作流「" + def.getName() + "」尚未发布，对话只能运行已发布版本");
        }
        return releases.findById(def.getDefaultReleaseId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "默认发布版本记录缺失，请重新发布"));
    }

    /**
     * 定义的默认发布版本号；从未发布或发布行缺失时为空。
     *
     * <p>只用于在建 run 时给草稿调试留一份「当时默认版本」快照；
     * 调用方不得拿它反推当前默认版本。
     *
     * @param def 已校验归属的定义
     * @return 默认发布版本号；没有默认发布指针或记录缺失时 null
     */
    private Integer defaultReleaseVersion(WorkflowDef def) {
        if (def == null || def.getDefaultReleaseId() == null) {
            return null;
        }
        return releases.findById(def.getDefaultReleaseId())
                .map(WorkflowRelease::getVersion)
                .orElse(null);
    }

    /**
     * 读取 run 详情；若停在人工卡点且有到期待办，按策略自动结算后再返回。
     *
     * @param id run 主键
     * @return 详情
     * @throws BizException 不存在或跨租户（统一 404）
     */
    public WorkflowRunDetail get(Long id) {
        long tenantId = tenantProvider.currentTenantId();
        WorkflowRun run = requireRun(id, tenantId);
        if (WorkflowRunStatus.parse(run.getStatus()) == WorkflowRunStatus.PAUSED_HUMAN) {
            // 懒检查：把到期待办按 AUTO_REJECT / AUTO_APPROVE 收口，可能顺带续跑
            humanTaskService.resolveExpired(run);
        }
        return detail(id, tenantId);    }

    /**
     * 取消运行。终态幂等返回；允许 CREATED / RUNNING / PAUSED_HUMAN 取消。
     *
     * @param id run 主键
     * @return 取消后的详情
     */
    public WorkflowRunDetail cancel(Long id) {
        long tenantId = tenantProvider.currentTenantId();
        WorkflowRun run = requireRun(id, tenantId);
        WorkflowRunStatus status = WorkflowRunStatus.parse(run.getStatus());
        if (status != null && status.terminal()) {
            return assembler.detail(run);
        }
        // CREATED 也允许取消：run 已落库但调度线程还没起（或刚开始）时，停止必须能覆盖到它。
        // 引擎侧用条件更新 CREATED→RUNNING，取消抢先落 CANCELLED 后不会再被启动覆盖。
        if (status != WorkflowRunStatus.CREATED
                && status != WorkflowRunStatus.RUNNING
                && status != WorkflowRunStatus.PAUSED_HUMAN
                && status != WorkflowRunStatus.WAITING_REPLY) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "当前运行状态为 " + status + "，不可取消");
        }
        Long operatorId = operatorContext.requireUserId();
        // 产品用语统一为「运行」：这条摘要会进工作流结果正文与库，不再写「试跑」
        String error = "用户取消运行";
        boolean changed = runs.updateStatusIfMatches(id, status.name(),
                WorkflowRunStatus.CANCELLED.name(), error, null, LocalDateTime.now(), operatorId);
        if (!changed) {
            /*
             * 与调度线程并发：读到的 CREATED 可能刚被引擎条件更新成 RUNNING。
             * 再读一次，若已进入未结束态就按新状态补一次取消；已经是终态才幂等返回。
             */
            WorkflowRun fresh = runs.findById(id).orElse(null);
            WorkflowRunStatus freshStatus = fresh == null ? null
                    : WorkflowRunStatus.parse(fresh.getStatus());
            if (freshStatus == WorkflowRunStatus.RUNNING
                    || freshStatus == WorkflowRunStatus.PAUSED_HUMAN
                    || freshStatus == WorkflowRunStatus.WAITING_REPLY) {
                changed = runs.updateStatusIfMatches(id, freshStatus.name(),
                        WorkflowRunStatus.CANCELLED.name(), error, null, LocalDateTime.now(), operatorId);
            }
            if (!changed) {
                // 与调度线程 / 人工处理并发：以库里的终态为准，不再重复写
                return detail(id, tenantId);
            }
        }
        nodeRuns.cancelOpen(id);
        for (WorkflowHumanTask task : humanTaskService.openTasks(id)) {
            humanTasks.decide(task.getId(), "EXPIRED",
                    "{\"cancelled\":true,\"reason\":\"run 被取消\"}", operatorId, operatorId);
        }
        // 取消不经过引擎：这里把对象状态对齐后收口根 trace（CANCELLED）
        run.setStatus(WorkflowRunStatus.CANCELLED.name());
        run.setErrorSummary(error);
        // 取消失败不能因为埋点异常回滚：观测是旁路，只告警
        try {
            traces.finishRun(run);
        } catch (RuntimeException ex) {
            log.warn("取消后收口 trace 失败 runId={} type={}", id, ex.getClass().getSimpleName());
        }
        operLogWriter.write("workflow", "run_cancel", "workflow_run", String.valueOf(id), "取消运行");
        /*
         * 取消不经过引擎线程，直播快照的 finally 收口可能还要等当前出站调用返回。
         * 这里立刻按库里的终态结算一次，让调试框的卡片与按钮马上收口；没有直播时安全空跑。
         */
        if (trialDispatcher != null) {
            trialDispatcher.settle(id);
        }
        return detail(id, tenantId);
    }

    /**
     * 工作台停止一轮时，按会话取消全部未结束的工作流 run。
     *
     * <p>为什么需要这个入口：工作台的停止接口只中断 ReAct 循环，
     * 工作流是同步跑在 ReAct 之外的引擎里、每节点前才重读库状态；
     * 不把 run 写成 CANCELLED，用户看到的就是「子 Agent 已中断、工作流还在跑」。
     * 这里复用 {@link #cancel(Long)}，不另造停止协议。
     *
     * <p>为什么用 REQUIRES_NEW：调用方（runtime 的会话取消）自己带事务，
     * 若加入同一事务，引擎线程在提交前读不到 CANCELLED，节点会多跑一拍。
     * 独立短事务先提交，引擎下一次检查即可退出。
     *
     * @param workbenchSessionId 工作台会话 id
     * @return 实际落到 CANCELLED 的 run 数；查询后被别的线程先收成成功 / 失败的不计
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelActiveByWorkbenchSession(Long workbenchSessionId) {
        if (workbenchSessionId == null) {
            return 0;
        }
        List<WorkflowRun> active = runs.listActiveByWorkbenchSession(workbenchSessionId);
        int canceled = 0;
        for (WorkflowRun run : active) {
            // 逐个走现网取消：终态幂等、人工待办一并收口、trace 由取消路径自己结
            cancel(run.getId());
            /*
             * 只把真正落到 CANCELLED 的算进停止数量：
             * 查询之后、取消之前 run 可能刚好被引擎收成 SUCCESS / FAILED，
             * 这时 cancel() 幂等返回、不会抛错；若仍计 1，调用方会误以为这一轮已停下。
             */
            WorkflowRun fresh = runs.findById(run.getId()).orElse(null);
            if (fresh != null
                    && WorkflowRunStatus.parse(fresh.getStatus()) == WorkflowRunStatus.CANCELLED) {
                canceled++;
            }
        }
        if (canceled > 0) {
            log.info("工作台停止连带取消工作流 sessionId={} count={}", workbenchSessionId, canceled);
        }
        return canceled;
    }

    /**
     * 某个工作流最近的 run，供试跑抽屉回显历史。
     *
     * @param workflowId 定义 id
     * @return 最多 10 条的 run 头（不含节点明细）
     */
    public List<WorkflowRun> recent(Long workflowId) {
        long tenantId = tenantProvider.currentTenantId();
        requireDef(workflowId, tenantId);
        return runs.listByWorkflowId(workflowId, 10);
    }

    /**
     * 等待回复节点上登记的可点选项：校验前端回传的 optionId 确实在这一步给出的选项里。
     *
     * <p>为什么必须在后端校验：点选与输入框提交共用同一条续跑写入，
     * 展示文案不能采信前端；认不出结构就明确拒绝，让用户直接输入。
     *
     * @param runId    run 主键
     * @param optionId 前端回传的选项标识
     * @return 该选项的服务端权威文案；run 不在等待 / 没有这个选项时为空
     */
    public Optional<String> waitingOptionLabel(Long runId, String optionId) {
        if (runId == null || optionId == null || optionId.isBlank()) {
            return Optional.empty();
        }
        String wanted = optionId.trim();
        for (WorkflowNodeRun row : nodeRuns.listByRunId(runId)) {
            if (row == null || WorkflowNodeRunStatus.parse(row.getStatus()) != WorkflowNodeRunStatus.WAITING_REPLY) {
                continue;
            }
            // 节点产出是自由形状 JSON：解析失败按「这一步没有选项」处理，不抛到点选链路
            JsonNode choice = readNodeChoice(row.getOutputJson());
            if (choice == null || !choice.isObject()) {
                continue;
            }
            for (JsonNode option : choice.path("options")) {
                String id = option.path("id").asText("").trim();
                String label = option.path("label").asText("").trim();
                if (!id.isEmpty() && id.equalsIgnoreCase(wanted) && !label.isEmpty()) {
                    return Optional.of(label);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 读节点产出里的 {@code choice} 节点；解析失败返回 null。
     *
     * @param outputJson 节点产出 JSON
     * @return choice 节点；没有或不可解析为 null
     */
    private JsonNode readNodeChoice(String outputJson) {
        if (outputJson == null || outputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(outputJson);
            return root instanceof ObjectNode body ? body.get("choice") : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 同一工作台会话里停在「等待用户回复」的 run。
     *
     * <p>续跑入口（用户下一句）靠它定位同一条运行；没有时调用方按正常对话处理。
     *
     * @param workbenchSessionId 工作台会话 id
     * @return 等待回复的 run；没有为空
     */
    public Optional<WorkflowRun> findWaitingReplyRun(long workbenchSessionId) {
        long tenantId = tenantProvider.currentTenantId();
        Optional<WorkflowRun> found = runs.findWaitingReplyByWorkbenchSession(workbenchSessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkflowRun run = found.get();
        // 跨租户的脏数据不参与续跑：按「没有等待中的运行」处理，不泄露别人的 run
        if (run.getTenantId() == null || run.getTenantId() != tenantId) {
            return Optional.empty();
        }
        return found;
    }

    /**
     * 工作流定义（卡片展示名 / 编码用）。定义被删时为空，调用方回落到编码兜底。
     *
     * @param run 运行实例
     * @return 定义；不存在为空
     */
    public Optional<WorkflowDef> definitionOf(WorkflowRun run) {
        if (run == null || run.getWorkflowId() == null) {
            return Optional.empty();
        }
        return defs.findById(run.getWorkflowId());
    }

    /**
     * 只读节点运行态，供对话回放按 runId 现算卡片节点产出。
     *
     * <p>为什么单独开一个入口：{@link #get(Long)} 会为人工卡点做超时懒结算（有副作用），
     * 也会组装待办；刷新时间线只需要节点行本身。这里不加权限注解、不写库、不触发调度，
     * 调用方已经通过会话归属校验，不会越权读到别人的 run。
     *
     * @param runId run 主键；可空
     * @return 按图顺序的节点行；runId 为空时为空表
     */
    public List<WorkflowNodeRun> cardNodes(Long runId) {
        if (runId == null) {
            return List.of();
        }
        return nodeRuns.listByRunId(runId);
    }

    /**
     * 批量取多张卡片对应的 run 头（只读）。回放补全需要 run 上的图快照来还原子图框。
     *
     * <p>一次刷新通常只有一两张工作流卡；这里按主键逐个读，避免为一个展示旁路新增批量查询。
     * 已删除 / 跨租户的 run 直接跳过，调用方保留骨架不补子图框。
     *
     * @param runIds run 主键集合；可空
     * @return runId → run；入参为空时为空表
     */
    public Map<Long, WorkflowRun> cardRunsOf(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, WorkflowRun> found = new LinkedHashMap<>();
        for (Long id : runIds) {
            if (id == null) {
                continue;
            }
            runs.findById(id).ifPresent(run -> found.put(id, run));
        }
        return found;
    }

    /**
     * 批量取多张卡片对应的节点行，按 runId 分组。
     *
     * <p>一次刷新可能带同一条会话里的多张工作流卡；用一次 IN 查询代替逐卡查询。
     * 没有节点行的 run 也会出现在结果里（值为空表），调用方据此保留骨架。
     *
     * @param runIds run 主键集合；可空
     * @return runId → 节点行（按图顺序）；入参为空时为空表
     */
    public Map<Long, List<WorkflowNodeRun>> cardNodesOf(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WorkflowNodeRun>> grouped = new LinkedHashMap<>();
        for (Long id : runIds) {
            if (id != null) {
                grouped.putIfAbsent(id, new ArrayList<>());
            }
        }
        if (grouped.isEmpty()) {
            return Map.of();
        }
        for (WorkflowNodeRun row : nodeRuns.listByRunIds(grouped.keySet())) {
            if (row == null || row.getRunId() == null) {
                continue;
            }
            List<WorkflowNodeRun> bucket = grouped.get(row.getRunId());
            if (bucket != null) {
                bucket.add(row);
            }
        }
        return grouped;
    }

    /**
     * 把用户在对话里的下一句送回同一条等待中的运行，并从挂起处继续调度。
     *
     * <p>与 {@link #runFromWorkbench} 的区别：这里<b>不新建 run</b>，也不经过入口判路由。
     * 流程：
     * <ol>
     *   <li>校验 run 仍停在 WAITING_REPLY，且确实有节点在等待回复；</li>
     *   <li>把回复写进 run 的 {@code input.reply}（条件边与后续节点的入参映射都读它）；</li>
     *   <li>等待中的节点迁 SUCCESS 并选中常规出边，run 回到 RUNNING；</li>
     *   <li>调用同一套引擎继续调度，结果由调用方投影回会话。</li>
     * </ol>
     *
     * @param runId      等待中的 run 主键
     * @param reply      用户在输入框发出的下一句（已去空白，非空）
     * @param userId     平台用户（会话归属人）
     * @param listener   节点状态观察者；可空
     * @return 续跑后的 run 详情（同步路径：返回时已跑到下一个等待点或终态）
     * @throws BizException run 不在等待回复 / 没有等待节点 / 状态已被并发改写
     */
    public WorkflowRunDetail resumeWithReply(Long runId, String reply, long userId, WorkflowRunListener listener) {
        WorkflowRun run = prepareReply(runId, reply, userId);
        // 4) 同一套引擎继续调度；终态 / 再次等待都由引擎自己落库
        executeSafely(run, userId, listener);
        WorkflowRun fresh = runs.findById(runId).orElse(run);
        return assembler.detail(fresh);
    }

    /**
     * 调试续跑：回复与状态迁移落库后立即返回，引擎交给后台线程继续。
     *
     * <p>与 {@link #resumeWithReply} 的差别只在最后一步：这里不占请求线程跑引擎，
     * 而是交给 {@link WorkflowTrialDispatcher}，与「开始调试」同一条后台通道。
     * 仍在同一条 run 上继续，不新建实例、不写工作台会话。
     *
     * <p>为什么必须异步：引擎一次续跑可能跑几十秒。若在请求线程里同步等，
     * 调试框的续跑请求会一直不返回，前端只能把「停止」按钮按请求在飞锁住，
     * 用户就没法在运行中点停止。
     *
     * @param runId  等待中的 run 主键
     * @param reply  用户下一句 / 选项文案（非空）
     * @param userId 平台用户（试跑归属人）
     * @return 续跑请求返回时的详情；状态已回到 RUNNING，引擎在后台继续
     * @throws BizException run 不在等待回复 / 没有等待节点 / 状态已被并发改写
     */
    public WorkflowRunDetail resumeWithReplyInBackground(Long runId, String reply, long userId) {
        WorkflowRun run = prepareReply(runId, reply, userId);
        /*
         * 租户与操作人在这里（请求线程）捕获后交给后台：后台线程不再读当前请求身份，
         * 避免池线程上取到别人的租户。回复正文一并带过去，直播投影要把它挂在询问节点上。
         */
        trialDispatcher.submit(runId, userId, run.getTenantId(), reply.trim());
        return detail(runId, run.getTenantId());
    }

    /**
     * 续跑的第一段（仍在请求线程）：回复落库、等待节点收成 SUCCESS、run 回到 RUNNING。
     *
     * <p>只做短事务内的状态迁移，不跑引擎；同步续跑与后台续跑共用这一段，
     * 保证两条路径写库口径完全一致。
     *
     * @param runId  等待中的 run 主键
     * @param reply  用户下一句 / 选项文案（非空）
     * @param userId 平台用户
     * @return 已回到 RUNNING 的运行实例（内存对象，状态已同步）
     * @throws BizException run 不在等待回复 / 没有等待节点 / 状态已被并发改写
     */
    private WorkflowRun prepareReply(Long runId, String reply, long userId) {
        if (reply == null || reply.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "回复内容不能为空");
        }
        long tenantId = tenantProvider.currentTenantId();
        WorkflowRun run = requireRun(runId, tenantId);
        if (WorkflowRunStatus.parse(run.getStatus()) != WorkflowRunStatus.WAITING_REPLY) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条运行已不在等待回复状态，请重新提问");
        }
        // 等待中的节点必须唯一：多节点同时等待会让「这一句回复给谁」变得不确定
        List<WorkflowNodeRun> waiting = nodeRuns.listByRunId(runId).stream()
                .filter(row -> WorkflowNodeRunStatus.parse(row.getStatus()) == WorkflowNodeRunStatus.WAITING_REPLY)
                .toList();
        if (waiting.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条运行没有等待回复的节点，请重新提问");
        }
        if (waiting.size() > 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "这条运行同时有 " + waiting.size() + " 个节点在等待回复，无法确定回复给哪一步");
        }
        WorkflowNodeRun waitingRow = waiting.get(0);

        // 1) 回复落库：覆盖同一个 input 对象，保留原始 query
        ObjectNode input = readInput(run.getInputJson());
        input.put("reply", reply.trim());
        String inputJson = input.toString();
        runs.updateInput(runId, inputJson, userId);
        run.setInputJson(inputJson);

        // 2) 等待节点收成 SUCCESS：保留已产出的问句，按常规出边继续
        List<WorkflowEdge> selected = normalEdges(run, waitingRow);
        waitingRow.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.WAITING_REPLY, WorkflowNodeRunStatus.SUCCESS).name());
        waitingRow.setEndTime(LocalDateTime.now());
        waitingRow.setSelectedEdges(joinIds(selected));
        nodeRuns.update(waitingRow);
        /*
         * 回复落库到等待节点：落库卡片只存骨架，刷新回放靠这一列把「我答了什么」补回节点。
         * 先截到列宽再写，避免长文本（「其它，我自己说」）撑破 varchar(2000)。
         */
        nodeRuns.markReply(runId, waitingRow.getNodeCode(), clipReply(reply.trim()));
        /*
         * 不在这里单独回报这一步：紧接着的 engine.execute 会先推一份「全图节点状态」的快照
         * （含本节点 SUCCESS）。提前只报一个节点会让卡片先闪成一张只有一个步骤的空壳。
         */

        // 3) run 回 RUNNING（条件迁移：与取消并发时以库为准）
        boolean changed = runs.updateStatusIfMatches(runId, WorkflowRunStatus.WAITING_REPLY.name(),
                WorkflowRunStatus.RUNNING.name(), null, null, null, userId);
        if (!changed) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条运行刚刚被取消或改写，已无法继续，请重新提问");
        }
        run.setStatus(WorkflowRunStatus.RUNNING.name());
        run.setErrorSummary(null);
        operLogWriter.write("workflow", "run_reply", "workflow_run", String.valueOf(runId),
                "对话回复续跑 run=" + runId);
        return run;
    }

    /**
     * 把等待节点的状态报给观察者（复用入口：续跑时先让卡片知道这一步已过）。
     *
     * <p>当前续跑流程不再调用它：引擎开跑时会统一推一次全图快照，单独先报一个节点
     * 会让卡片先闪成单步空壳。保留给将来需要「只报一格」的路径，且始终是旁路。
     *
     * @param listener 观察者；可空
     * @param run      运行实例（取图用）
     * @param row      已更新的运行态行
     */
    private void publishNode(WorkflowRunListener listener, WorkflowRun run, WorkflowNodeRun row) {
        if (listener == null || run == null || row == null) {
            return;
        }
        try {
            WorkflowGraph graph = codec.parse(run.getGraphJson());
            WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
            WorkflowNode node = runtime.node(row.getNodeId());
            if (node != null) {
                listener.nodeChanged(node, row);
            }
        } catch (RuntimeException ex) {
            // 观察是旁路：画不出这一步不能影响续跑
            log.warn("续跑后回报节点状态失败 runId={} nodeId={} type={}",
                    run.getId(), row.getNodeId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * 读 run 的输入快照成对象（脏数据按空对象处理，不抛到续跑链路）。
     *
     * @param raw input_json
     * @return 可变对象
     */
    private ObjectNode readInput(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(raw);
            return node instanceof ObjectNode object ? object : json.createObjectNode();
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }

    /**
     * 取节点常规出边（NORMAL / COND / DEFAULT）；图损坏时按「无出边」处理。
     *
     * @param run 运行实例
     * @param row 等待中的节点行
     * @return 出边；没有时为空表
     */
    private List<WorkflowEdge> normalEdges(WorkflowRun run, WorkflowNodeRun row) {
        List<WorkflowEdge> edges = new ArrayList<>();
        try {
            WorkflowGraph graph = codec.parse(run.getGraphJson());
            /*
             * 等待中的节点可能在子图内部：它的 node_id 带范围前缀，根图里查不到出边。
             * 用与回放同一套范围还原找到它属于哪一层，再按那层的出边续跑；
             * 找不到（老数据 / 单测没装还原器）退回根图，行为与改动前一致。
             */
            WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
            String nodeId = row.getNodeId();
            if (scopeTreeBuilder != null && nodeId != null && !nodeId.isBlank()) {
                WorkflowScope scope = scopeTreeBuilder
                        .build(run, graph, nodeRuns.listByRunId(run.getId()))
                        .scopeOfNode(nodeId);
                if (scope != null) {
                    runtime = scope.runtime();
                }
            }
            for (WorkflowEdge edge : runtime.outgoing(nodeId)) {
                if (WorkflowEdgeType.parseOrDefault(edge.getType()) != WorkflowEdgeType.ERROR) {
                    edges.add(edge);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("读取等待节点出边失败 runId={} nodeId={} type={}",
                    run.getId(), row.getNodeId(), ex.getClass().getSimpleName());
        }
        return edges;
    }

    private String joinIds(List<WorkflowEdge> edges) {
        StringBuilder builder = new StringBuilder();
        for (WorkflowEdge edge : edges) {
            if (edge.getId() == null || edge.getId().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(edge.getId());
        }
        return builder.toString();
    }

    /**
     * 用户回复落库前的截断。
     *
     * <p>列宽 {@code varchar(2000)}：这里按字符截断并追省略号，避免「其它，我自己说」
     * 这类长回复在严格模式下抛错、把整次续跑打成失败。
     *
     * @param reply 用户回复（已去空白、非空）
     * @return 不超过列宽的文本
     */
    private static String clipReply(String reply) {
        String text = reply == null ? "" : reply;
        if (text.length() <= REPLY_MAX) {
            return text;
        }
        return text.substring(0, REPLY_MAX - 1) + "…";
    }

    private WorkflowRunDetail detail(Long runId, long tenantId) {
        WorkflowRun fresh = requireRun(runId, tenantId);
        return assembler.detail(fresh);
    }

    /**
     * 解析本次试跑绑定的 release：显式 releaseId 优先；否则用定义的默认发布指针。
     */
    private WorkflowRelease resolveRelease(WorkflowRunTrialReq req, long tenantId) {
        if (req.getReleaseId() != null) {
            WorkflowRelease release = releases.findById(req.getReleaseId())
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "发布版本不存在"));
            requireDef(release.getWorkflowId(), tenantId);
            if (req.getWorkflowId() != null && !req.getWorkflowId().equals(release.getWorkflowId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "发布版本不属于该工作流");
            }
            return release;
        }
        if (req.getWorkflowId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请提供 releaseId 或 workflowId");
        }
        WorkflowDef def = requireDef(req.getWorkflowId(), tenantId);
        if (def.getDefaultReleaseId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该工作流尚未发布，调试只能使用已发布版本；请先发布");
        }
        return releases.findById(def.getDefaultReleaseId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "默认发布版本记录缺失，请重新发布"));
    }

    private WorkflowDef requireDef(Long id, long tenantId) {
        WorkflowDef def = defs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在"));
        if (def.getTenantId() == null || def.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        return def;
    }

    private WorkflowRun requireRun(Long id, long tenantId) {
        WorkflowRun run = runs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "运行实例不存在"));
        if (run.getTenantId() == null || run.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行实例不存在");
        }
        return run;
    }

    private List<WorkflowNodeRun> pendingRows(Long runId, WorkflowRuntimeGraph runtime) {
        List<WorkflowNodeRun> rows = new ArrayList<>();
        for (WorkflowNode node : runtime.nodes()) {
            WorkflowNodeRun row = new WorkflowNodeRun();
            row.setRunId(runId);
            row.setNodeId(node.getId());
            row.setNodeCode(node.getCode());
            row.setNodeType(node.getType());
            row.setAttempt(1);
            row.setStatus("PENDING");
            rows.add(row);
        }
        return rows;
    }

    /**
     * 详情响应转换（避免 Controller 直接摸 domain 模型）。
     *
     * @param detail 聚合
     * @return API DTO
     */
    public WorkflowRunDetailResp toResp(WorkflowRunDetail detail) {
        return WorkflowRunDetailResp.of(detail, json);
    }

    /**
     * 历史记录一行：run 头 + 这次调试开始时的用户问题。
     *
     * <p>问题直接取落库的 {@code input_json.query}，续跑回复只追加 {@code reply}、不改 query，
     * 所以回看旧记录时拿到的仍是第一句。没有 input 或 query 的旧记录给空串，不补猜。
     *
     * @param run run 头
     * @return 摘要 DTO
     */
    public WorkflowRunSummaryResp toSummary(WorkflowRun run) {
        return WorkflowRunSummaryResp.of(run, queryOf(run.getInputJson()));
    }

    /**
     * 从 run 的输入快照里取 {@code query}。
     *
     * @param inputJson 落库的输入 JSON；可空
     * @return 用户问题；解析失败或没有 query 时为空串
     */
    private String queryOf(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = json.readTree(inputJson);
            JsonNode query = root == null ? null : root.get("query");
            return query != null && query.isTextual() ? query.asText() : "";
        } catch (Exception ex) {
            // 脏输入按「没有记下问题」处理：列表留空，不影响其它列
            return "";
        }
    }
}
