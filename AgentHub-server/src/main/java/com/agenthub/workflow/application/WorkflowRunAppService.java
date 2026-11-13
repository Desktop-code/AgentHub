package com.agenthub.workflow.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.workflow.api.dto.WorkflowRunDetailResp;
import com.agenthub.workflow.api.dto.WorkflowRunTrialReq;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.model.WorkflowWorkbenchOutcome;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作流试跑 / 运行详情 / 取消 用例。所属域：workflow 的 application。
 * <p>边界：只跑**已发布 release** 快照（禁可变草稿）；AGENT / TOOL 出站交给引擎端口；
 * 控制台入口只产 TRIAL；应用入站（{@code trigger_type=APP}）走
 * {@code WorkflowAppInvokeAppService}，同样绑 release 且共用本引擎。
 * <p>为什么 createTrial 不是事务：引擎内会调模型 / 工具（秒级到分钟级），
 * 长事务会占连接并放大锁；改为「建 run + 预建节点行」短事务后同步调度，
 * 调度期每一步自己落状态，失败也能从库表追溯。
 */
@Service
public class WorkflowRunAppService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunAppService.class);

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
                                 WorkflowTraceWriter traces) {
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
    }

    /**
     * 发起一次试跑。绑定 release 快照后同步推进到终态或人工卡点。
     *
     * @param req releaseId 或 workflowId+默认发布指针，以及输入样例
     * @return 最新 run 详情
     * @throws BizException 未发布 / 版本不存在 / 输入不是对象 / 图不可执行
     */
    public WorkflowRunDetail trial(WorkflowRunTrialReq req) {
        long tenantId = tenantProvider.currentTenantId();
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
        engine.execute(run, operatorId);
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
            }
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
        executeSafely(run, userId);
        WorkflowRun fresh = runs.findById(runId).orElse(run);
        return new WorkflowWorkbenchOutcome(fresh, false);
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
        try {
            engine.execute(run, userId);
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
        return detail(id, tenantId);
    }

    /**
     * 取消试跑。终态幂等返回；只允许 RUNNING / PAUSED_HUMAN 取消。
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
        if (status != WorkflowRunStatus.RUNNING && status != WorkflowRunStatus.PAUSED_HUMAN) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "当前运行状态为 " + status + "，不可取消");
        }
        Long operatorId = operatorContext.requireUserId();
        String error = "用户取消试跑";
        boolean changed = runs.updateStatusIfMatches(id, status.name(),
                WorkflowRunStatus.CANCELLED.name(), error, null, LocalDateTime.now(), operatorId);
        if (!changed) {
            // 与调度线程 / 人工处理并发：以库里的终态为准，不再重复写
            return detail(id, tenantId);
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
        operLogWriter.write("workflow", "run_cancel", "workflow_run", String.valueOf(id), "取消试跑");
        return detail(id, tenantId);
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
                    "该工作流尚未发布，试跑只能绑定已发布版本；请先点「发布」");
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
}
