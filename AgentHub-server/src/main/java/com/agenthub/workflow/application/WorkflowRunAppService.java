package com.agenthub.workflow.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
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
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
                                 ObjectMapper json) {
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
