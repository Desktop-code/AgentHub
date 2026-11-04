package com.agenthub.workflow.infra;

import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsPlaceholders;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流埋点实现。所属域：workflow 的 infra。
 *
 * <p>只做「工作流语义 → obs 语义」的翻译：一次 run 一条根 trace（channel=WORKFLOW，
 * {@code workflow_run_id} 写 run 主键），每个节点一条 TASK 子步（耗时 / 脱敏入出参 / nodeType / status）。
 * 真正的落库仍走 obs 的 {@link TraceRecorder}，这里不重复实现写入、不建第二张表。
 *
 * <p>降级：所有方法整体 try/catch，失败只记 WARN，绝不把异常抛回引擎；
 * 埋点缺失时业务照常跑完，只是 Trace 页看不到这次 run。
 * <p>幂等：{@code TraceRecorder.finish} 自身只更新仍为 RUNNING 的行，重复收尾是安全的。
 */
@Component
public class WorkflowObsTraceWriter implements WorkflowTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowObsTraceWriter.class);

    /** 入 / 出参摘要上限：节点实参可能很大，obs 只留可排障的摘要 */
    private static final int TEXT_LIMIT = 4000;

    private final TraceRecorder recorder;
    private final WorkflowDefRepository defs;
    private final WorkflowRunRepository runs;

    /** runId → 当前 trace 范围；收尾时移除，避免长跑进程堆积 */
    private final ConcurrentHashMap<Long, ObsTraceScope> scopes = new ConcurrentHashMap<>();
    /** runId → (画布 nodeId → obs_span 主键)；节点收尾时逐个移除 */
    private final ConcurrentHashMap<Long, Map<String, Long>> nodeSpanIds = new ConcurrentHashMap<>();

    /**
     * @param recorder obs 写入端口（与控制台试跑共用同一实现）
     * @param defs     工作流定义（取名称 / 编码写进 trace 标题与 attrs）
     * @param runs     run 仓储（回写 trace_id）
     */
    public WorkflowObsTraceWriter(TraceRecorder recorder, WorkflowDefRepository defs, WorkflowRunRepository runs) {
        this.recorder = recorder;
        this.defs = defs;
        this.runs = runs;
    }

    /**
     * {@inheritDoc}
     * <p>首次运行：建根 trace 并回写 {@code workflow_run.trace_id}，人工通过后恢复调度时据此续用同一条。
     */
    @Override
    public void beginRun(WorkflowRun run) {
        try {
            if (run == null || run.getId() == null) {
                return;
            }
            // 已有 trace_id：说明是 HUMAN 暂停后的续跑，接着往同一条 trace 写
            if (run.getTraceId() != null && !run.getTraceId().isBlank()) {
                recorder.resumeWorkflow(run.getTraceId())
                        .ifPresent(scope -> scopes.put(run.getId(), scope));
                return;
            }
            WorkflowDef def = run.getWorkflowId() == null
                    ? null : defs.findById(run.getWorkflowId()).orElse(null);
            String name = displayName(def, run.getWorkflowId());
            String attrsJson = ObsAttrs.withFields(null, traceAttrs(run, def, name));
            long userId = run.getTriggeredBy() == null
                    ? ObsPlaceholders.SYSTEM_USER_ID : run.getTriggeredBy();
            String title = "工作流 · " + name + " · v"
                    + (run.getReleaseVersion() == null ? "—" : run.getReleaseVersion());
            // 工作流 run 没有对话会话：sessionId 用约定占位（obs_trace.session_id NOT NULL）
            recorder.beginWorkflow(
                            new TraceStart(ObsPlaceholders.SESSION_NONE, null, userId, title),
                            String.valueOf(run.getId()), attrsJson)
                    .ifPresent(scope -> {
                        scopes.put(run.getId(), scope);
                        run.setTraceId(scope.traceId());
                        runs.attachTraceId(run.getId(), scope.traceId());
                    });
        } catch (RuntimeException ex) {
            log.warn("工作流 trace 开启失败 runId={} type={}",
                    run == null ? null : run.getId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     * <p>节点步挂根 trace 下（parent 为空即根的子步），不强行编造中间层：
     * 现网试快照的 Agent 内部 LLM/TOOL 步不由本刀接管，避免出现两套父子链。
     */
    @Override
    public void beginNode(WorkflowRun run, WorkflowNode node) {
        try {
            ObsTraceScope scope = scopeOf(run);
            if (scope == null || node == null || node.getId() == null) {
                return;
            }
            Long spanId = scope.open(SpanType.TASK, nodeName(node), null, null, null);
            if (spanId != null) {
                nodeSpanIds.computeIfAbsent(run.getId(), ignored -> new ConcurrentHashMap<>())
                        .put(node.getId(), spanId);
            }
        } catch (RuntimeException ex) {
            log.warn("工作流节点 span 开启失败 runId={} node={} type={}",
                    run == null ? null : run.getId(), node == null ? null : node.getCode(),
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     * <p>WAITING_HUMAN 不收口：人工卡点的耗时算到根 trace 收尾为止，避免「等待」被记成节点耗时。
     */
    @Override
    public void endNode(WorkflowRun run, WorkflowNode node, WorkflowNodeRun row) {
        try {
            if (run == null || run.getId() == null || node == null || node.getId() == null || row == null) {
                return;
            }
            Map<String, Long> spans = nodeSpanIds.get(run.getId());
            Long spanId = spans == null ? null : spans.remove(node.getId());
            if (spanId == null) {
                return;
            }
            ObsStatus status = nodeStatus(row.getStatus());
            if (status == null) {
                return;
            }
            ObsTraceScope scope = scopeOf(run);
            if (scope == null) {
                return;
            }
            SpanClose close = new SpanClose();
            close.setStatus(status);
            close.setInputText(summarize(row.getInputJson()));
            close.setOutputText(summarize(row.getOutputJson()));
            // 失败 / 取消才写失败摘要，成功路径不硬编造
            if (status != ObsStatus.OK) {
                close.setErrorSummary(summarize(row.getErrorSummary()));
            }
            close.setAttrsJson(ObsAttrs.withFields(null, nodeAttrs(row)));
            scope.close(spanId, close);
        } catch (RuntimeException ex) {
            log.warn("工作流节点 span 收口失败 runId={} node={} type={}",
                    run == null ? null : run.getId(), node == null ? null : node.getCode(),
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     * <p>根 trace 收尾时，库里仍为 RUNNING 的步骤由 obs 侧统一按同一终态收口
     * （例如取消时正在跑的节点、以及停在人工卡点的节点）。
     */
    @Override
    public void finishRun(WorkflowRun run) {
        try {
            if (run == null || run.getId() == null) {
                return;
            }
            ObsStatus status = runStatus(run.getStatus());
            if (status == null) {
                return;
            }
            ObsTraceScope scope = scopes.remove(run.getId());
            if (scope == null && run.getTraceId() != null && !run.getTraceId().isBlank()) {
                // 跨线程 / 进程内状态丢失时兜底：按 traceId 续一次，只为收尾
                scope = recorder.resumeWorkflow(run.getTraceId()).orElse(null);
            }
            if (scope == null) {
                return;
            }
            // run 对象上的 errorSummary 已由状态机写入；obs 侧会再打一次码
            recorder.finish(scope, status, summarize(run.getErrorSummary()));
        } catch (RuntimeException ex) {
            log.warn("工作流 trace 收尾失败 runId={} type={}",
                    run == null ? null : run.getId(), ex.getClass().getSimpleName());
        } finally {
            if (run != null && run.getId() != null) {
                nodeSpanIds.remove(run.getId());
            }
        }
    }

    /**
     * 取（或重建）本 run 的 trace 范围。
     *
     * @param run run
     * @return 范围；未接线 / 已终态时为 null
     */
    private ObsTraceScope scopeOf(WorkflowRun run) {
        if (run == null || run.getId() == null) {
            return null;
        }
        ObsTraceScope scope = scopes.get(run.getId());
        if (scope != null) {
            return scope;
        }
        if (run.getTraceId() == null || run.getTraceId().isBlank()) {
            return null;
        }
        ObsTraceScope resumed = recorder.resumeWorkflow(run.getTraceId()).orElse(null);
        if (resumed != null) {
            scopes.put(run.getId(), resumed);
        }
        return resumed;
    }

    /**
     * 根 trace 的编排扩展信息（全部为展示 / 排障字段，无密钥）。
     */
    private Map<String, Object> traceAttrs(WorkflowRun run, WorkflowDef def, String name) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("workflow_def_id", run.getWorkflowId());
        attrs.put("workflow_name", name);
        if (def != null && def.getCode() != null) {
            attrs.put("workflow_code", def.getCode());
        }
        attrs.put("workflow_run_id", run.getId());
        attrs.put("release_id", run.getReleaseId());
        attrs.put("release_version", run.getReleaseVersion());
        attrs.put("trigger_type", run.getTriggerType());
        return attrs;
    }

    /**
     * 节点步扩展信息：节点 id / 编码 / 类型 / 尝试序号 / 选中的出边。
     */
    private Map<String, Object> nodeAttrs(WorkflowNodeRun row) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("node_id", row.getNodeId());
        attrs.put("node_code", row.getNodeCode());
        attrs.put("node_type", row.getNodeType());
        attrs.put("attempt", row.getAttempt());
        if (row.getSelectedEdges() != null && !row.getSelectedEdges().isBlank()) {
            attrs.put("selected_edges", row.getSelectedEdges());
        }
        return attrs;
    }

    /**
     * 节点步展示名：编码 + 类型，便于在树里一眼看出是什么节点。
     */
    private String nodeName(WorkflowNode node) {
        String code = node.getCode() == null || node.getCode().isBlank() ? node.getId() : node.getCode();
        return node.getType() == null || node.getType().isBlank() ? code : code + " · " + node.getType();
    }

    /**
     * 工作流定义名；查不到时退回 id，保证标题不为空。
     */
    private String displayName(WorkflowDef def, Long workflowId) {
        if (def != null && def.getName() != null && !def.getName().isBlank()) {
            return def.getName();
        }
        return "工作流 #" + workflowId;
    }

    /**
     * 节点终态映射；RUNNING / WAITING_HUMAN / PENDING 返回 null 表示「先不收口」。
     */
    private ObsStatus nodeStatus(String raw) {
        WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(raw);
        if (status == null) {
            return null;
        }
        return switch (status) {
            case SUCCESS, SKIPPED -> ObsStatus.OK;
            case FAILED -> ObsStatus.ERROR;
            case CANCELLED -> ObsStatus.CANCELLED;
            default -> null;
        };
    }

    /**
     * run 终态映射；非终态（含 PAUSED_HUMAN）返回 null，根 trace 保持 RUNNING。
     */
    private ObsStatus runStatus(String raw) {
        WorkflowRunStatus status = WorkflowRunStatus.parse(raw);
        if (status == null) {
            return null;
        }
        return switch (status) {
            case SUCCESS -> ObsStatus.OK;
            case FAILED -> ObsStatus.ERROR;
            case CANCELLED -> ObsStatus.CANCELLED;
            default -> null;
        };
    }

    /**
     * 摘要化：超长正文只留前 N 字符；空白归一成 null（由 obs 侧决定不写空串）。
     */
    private String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT) + "…（已截断）";
    }
}
