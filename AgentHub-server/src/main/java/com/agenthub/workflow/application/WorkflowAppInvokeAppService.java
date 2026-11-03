package com.agenthub.workflow.application;

import com.agenthub.admin.domain.model.AppIdentity;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.workflow.api.dto.WorkflowAppInvokeReq;
import com.agenthub.workflow.api.dto.WorkflowAppInvokeResp;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.AppWorkflowGrant;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 应用入站触发工作流。所属域：workflow 的 application。
 * <p>语义：app_key + secret 校验通过（admin 域拦截器）→ 校验 grant → 只绑**已发布 release 快照**
 * → 建 {@code trigger_type=APP} 的 run → 交给同一个 {@link WorkflowEngine} 异步调度。
 * <p>为什么异步：OpenAPI 是机器调用，不应被一次 ReAct / 工具出站拖住 HTTP；
 * 与试跑共用引擎和硬顶，不新开第二套执行循环。
 * <p>线程：调度任务在该线程内执行，<b>没有</b> Sa-Token 上下文；因此 operatorId 统一用
 * {@link #SYSTEM_OPERATOR_ID}=0 记审计（平台用户为 null 语义），机器身份靠 run.app_id 追溯。
 * <p>幂等：同 app 下 client_request_id 唯一（DB 唯一索引 + 先查后插兜底），重复提交返回已有 run。
 */
@Slf4j
@Service
public class WorkflowAppInvokeAppService {

    /** 应用触发的审计操作人：0 = 系统（无平台用户）；真实来源看 run.app_id */
    public static final long SYSTEM_OPERATOR_ID = 0L;

    /** clientRequestId 与库列长度一致 */
    private static final int CLIENT_REQUEST_ID_MAX = 64;

    private final WorkflowAppGrantAppService grantService;
    private final WorkflowDefRepository defs;
    private final WorkflowReleaseRepository releases;
    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowTenantProvider tenantProvider;
    private final WorkflowGraphCodec codec;
    private final WorkflowEngine engine;
    private final WorkflowProperties properties;
    private final OperLogWriter operLogWriter;
    private final ObjectMapper json;
    private final Executor executor;

    /**
     * @param grantService    授权校验
     * @param defs            定义仓储
     * @param releases        发布快照仓储
     * @param runs            run 仓储
     * @param nodeRuns        节点运行态仓储
     * @param tenantProvider  当前租户
     * @param codec           图解析
     * @param engine          调度引擎（与试跑同一套）
     * @param properties      硬顶（max-node-runs-per-run）
     * @param operLogWriter   操作日志（只记 run 元数据）
     * @param json            全站 ObjectMapper
     * @param executor        异步调度线程池（routerTaskExecutor，有界 + CallerRuns）
     */
    public WorkflowAppInvokeAppService(WorkflowAppGrantAppService grantService,
                                       WorkflowDefRepository defs,
                                       WorkflowReleaseRepository releases,
                                       WorkflowRunRepository runs,
                                       WorkflowNodeRunRepository nodeRuns,
                                       WorkflowTenantProvider tenantProvider,
                                       WorkflowGraphCodec codec,
                                       WorkflowEngine engine,
                                       WorkflowProperties properties,
                                       OperLogWriter operLogWriter,
                                       ObjectMapper json,
                                       @Qualifier("routerTaskExecutor") Executor executor) {
        this.grantService = grantService;
        this.defs = defs;
        this.releases = releases;
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.tenantProvider = tenantProvider;
        this.codec = codec;
        this.engine = engine;
        this.properties = properties;
        this.operLogWriter = operLogWriter;
        this.json = json;
        this.executor = executor;
    }

    /**
     * 创建并异步调度一次应用触发的 run。
     *
     * @param app 已通过 app_key + secret 校验的应用身份
     * @param req workflowId + input（+ 可选幂等键）
     * @return runId / 初始状态 / 实际绑定的 releaseId
     * @throws BizException 未授权、未发布、输入非法、节点数超顶
     */
    public WorkflowAppInvokeResp invoke(AppIdentity app, WorkflowAppInvokeReq req) {
        if (app == null || app.getAppId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "应用未授权或密钥错误");
        }
        long tenantId = tenantProvider.currentTenantId();
        WorkflowDef def = requireDef(req.getWorkflowId(), tenantId);
        // 授权先于发布解析：未授权时不泄露「该工作流是否存在已发布版本」
        AppWorkflowGrant grant = grantService.requireEnabled(app.getAppId(), def.getId());
        WorkflowRelease release = resolveRelease(def, grant);

        JsonNode input = normaliseInput(req.getInput());
        String requestId = normaliseRequestId(req.getClientRequestId());

        // 幂等：同 app 同 requestId 直接复用已有 run（先查一次，减少唯一键冲突的日志噪音）
        if (requestId != null) {
            WorkflowRun existing = runs.findByAppAndClientRequestId(app.getAppId(), requestId).orElse(null);
            if (existing != null) {
                return resp(existing);
            }
        }

        // 建 run 前确认图可执行且节点数在上限内，避免留下「永远动不了」的脏实例
        WorkflowGraph graph = codec.parse(release.getGraphJson());
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        int nodeRunsCap = properties.effectiveMaxNodeRunsPerRun();
        if (runtime.nodes().size() > nodeRunsCap) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该图有 " + runtime.nodes().size() + " 个节点，超过单次运行上限 " + nodeRunsCap
                            + "（agenthub.workflow.max-node-runs-per-run）；请联系平台管理员拆分工作流");
        }

        WorkflowRun run = new WorkflowRun();
        run.setTenantId(tenantId);
        run.setWorkflowId(def.getId());
        run.setReleaseId(release.getId());
        run.setReleaseVersion(release.getVersion());
        run.setFingerprint(release.getFingerprint());
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.APP.name());
        // 无平台用户：triggeredBy 留空，应用归属写 appId
        run.setTriggeredBy(null);
        run.setAppId(app.getAppId());
        run.setClientRequestId(requestId);
        run.setInputJson(input.toString());
        // 快照拷进 run：之后再发布新版本也不影响这次调用
        run.setGraphJson(release.getGraphJson());
        run.setStartTime(LocalDateTime.now());
        run.setCreatedBy(null);

        Long runId;
        try {
            runId = runs.insert(run);
        } catch (DuplicateKeyException ex) {
            // 并发重复提交：唯一键兜底，返回先落库的那条
            WorkflowRun raced = requestId == null ? null
                    : runs.findByAppAndClientRequestId(app.getAppId(), requestId).orElse(null);
            if (raced != null) {
                return resp(raced);
            }
            throw ex;
        }
        run.setId(runId);
        nodeRuns.insertBatch(pendingRows(runId, runtime));

        // 审计只记元数据：不写 input 正文、不写 secret
        operLogWriter.write("workflow", "app_invoke", "workflow_run", String.valueOf(runId),
                "应用触发 code=" + def.getCode() + " app=" + app.getAppKey() + " release=" + release.getId());

        dispatch(run);
        return resp(run);
    }

    /**
     * 异步调度；与试跑同一引擎。
     * <p>异常不会冒到 HTTP（此时已返回 runId），这里兜底把 run 标 FAILED，避免永远停在 CREATED。
     */
    private void dispatch(WorkflowRun run) {
        executor.execute(() -> {
            try {
                engine.execute(run, SYSTEM_OPERATOR_ID);
            } catch (RuntimeException ex) {
                log.warn("应用触发调度异常 runId={} appId={} root={}",
                        run.getId(), run.getAppId(), ex.getClass().getSimpleName(), ex);
                runs.updateStatus(run.getId(), WorkflowRunStatus.FAILED.name(),
                        "应用触发调度异常，请稍后重试或联系平台管理员（runId=" + run.getId() + "）",
                        null, LocalDateTime.now(), SYSTEM_OPERATOR_ID);
            }
        });
    }

    /**
     * 解析本次要跑的 release：grant 钉死优先，否则跟 def 的默认发布指针。
     *
     * @throws BizException 未发布 / 钉死版本缺失 / 版本不属于该定义
     */
    private WorkflowRelease resolveRelease(WorkflowDef def, AppWorkflowGrant grant) {
        if (grant.getReleaseId() != null) {
            WorkflowRelease pinned = releases.findById(grant.getReleaseId())
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                            "该应用钉死的发布版本已缺失，请联系平台管理员重新授权"));
            if (!def.getId().equals(pinned.getWorkflowId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "授权绑定的发布版本不属于该工作流");
            }
            return pinned;
        }
        if (def.getDefaultReleaseId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该工作流尚未发布，应用不可触发；请先在编排页发布版本");
        }
        return releases.findById(def.getDefaultReleaseId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "默认发布版本记录缺失，请重新发布后再授权"));
    }

    private WorkflowDef requireDef(Long workflowId, long tenantId) {
        if (workflowId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "workflowId 不能为空");
        }
        WorkflowDef def = defs.findById(workflowId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在"));
        if (def.getTenantId() == null || def.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        return def;
    }

    private JsonNode normaliseInput(JsonNode input) {
        if (input == null || input.isNull()) {
            return json.createObjectNode();
        }
        if (!input.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "input 必须是 JSON 对象，例如 {\"query\":\"...\"}");
        }
        return input;
    }

    private String normaliseRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > CLIENT_REQUEST_ID_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "clientRequestId 长度不能超过 " + CLIENT_REQUEST_ID_MAX + " 个字符");
        }
        return trimmed;
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

    private WorkflowAppInvokeResp resp(WorkflowRun run) {
        return new WorkflowAppInvokeResp(run.getId(), run.getStatus(), run.getWorkflowId(),
                run.getReleaseId(), run.getTriggerType());
    }
}
