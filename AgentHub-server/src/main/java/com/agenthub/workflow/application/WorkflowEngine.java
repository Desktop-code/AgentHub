package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.text.TextClip;
import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.enums.HumanTimeoutPolicy;
import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.JsonLogicEvaluator;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import com.agenthub.workflow.domain.service.WorkflowAssigneeRule;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import com.agenthub.workflow.domain.service.WorkflowNodeInputs;
import com.agenthub.workflow.domain.service.WorkflowStateMachine;
import com.agenthub.workflow.domain.service.WorkflowToolInvoker;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 调度引擎。所属域：workflow 的 application。
 * <p>职责：按已发布快照推进节点、落 {@code workflow_node_run} 状态、在 HUMAN 处暂停，
 * 失败时按「ERROR 边优先，否则 FAIL_RUN」收束。
 * <p>边界：只做调度；AGENT / TOOL 的实际出站通过 {@link WorkflowAgentInvoker} /
 * {@link WorkflowToolInvoker} 复用现网入口，引擎内不出现模型 / MCP 细节。
 * <p>线程模型：一次调用在请求线程内同步跑完（或停在 HUMAN），
 * FORK 分支按图顺序**顺序**执行而非真并发，语义上等待 JOIN 等齐。
 * <p>取消：协作式——每执行一个节点前重读 run 状态，发现 CANCELLED 立即退出。
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 节点级重试本刀不做；attempt 恒 1 */
    private static final int ATTEMPT = 1;

    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowHumanTaskRepository humanTasks;
    private final WorkflowAgentInvoker agentInvoker;
    private final WorkflowToolInvoker toolInvoker;
    private final WorkflowGraphCodec codec;
    private final WorkflowProperties properties;
    private final ObjectMapper json;
    /** 埋点端口：根 trace + 每节点 TASK 步；实现失败只记日志，不影响调度 */
    private final WorkflowTraceWriter traces;

    /**
     * @param runs         run 仓储
     * @param nodeRuns     node_run 仓储
     * @param humanTasks   人工待办仓储
     * @param agentInvoker AGENT 节点执行口（现网试跑链路）
     * @param toolInvoker  TOOL 节点执行口（现网 MCP）
     * @param codec        图 JSON 解析
     * @param properties   墙钟 / 人工超时默认值
     * @param json         全站 ObjectMapper
     * @param traces       工作流埋点端口
     */
    public WorkflowEngine(WorkflowRunRepository runs,
                          WorkflowNodeRunRepository nodeRuns,
                          WorkflowHumanTaskRepository humanTasks,
                          WorkflowAgentInvoker agentInvoker,
                          WorkflowToolInvoker toolInvoker,
                          WorkflowGraphCodec codec,
                          WorkflowProperties properties,
                          ObjectMapper json,
                          WorkflowTraceWriter traces) {
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.humanTasks = humanTasks;
        this.agentInvoker = agentInvoker;
        this.toolInvoker = toolInvoker;
        this.codec = codec;
        this.properties = properties;
        this.json = json;
        this.traces = traces;
    }

    /**
     * 从当前持久化状态继续调度，直到终态或停在 HUMAN。
     * <p>调用前 run 必须是 CREATED（首次试跑）或 RUNNING（人工通过后续跑）。
     *
     * @param run        已落库的 run
     * @param operatorId 操作人
     * @throws BizException run 状态不允许调度 / 图不可执行
     */
    public void execute(WorkflowRun run, long operatorId) {
        WorkflowRunStatus current = WorkflowRunStatus.parse(run.getStatus());
        if (current == WorkflowRunStatus.CREATED) {
            // 首次调度：CREATED → RUNNING，并补开始时间
            transitionRun(run, WorkflowRunStatus.CREATED, WorkflowRunStatus.RUNNING,
                    null, null, operatorId);
            run.setStartTime(LocalDateTime.now());
        } else if (current != WorkflowRunStatus.RUNNING) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "当前运行状态为 " + WorkflowStateMachine.label(current) + "，不可继续调度");
        }

        WorkflowGraph graph = codec.parse(run.getGraphJson());
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        ObjectNode input = readObject(run.getInputJson());
        List<WorkflowNodeRun> rows = ensureNodeRuns(run, runtime);
        Map<String, WorkflowNodeRun> rowByNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : rows) {
            rowByNode.put(row.getNodeId(), row);
        }
        ObjectNode outputs = collectOutputs(runtime, rows);
        ObjectNode ctx = JsonLogicEvaluator.context(input, outputs, json.createObjectNode());

        long wallMs = properties.effectiveWallMs();
        long deadline = System.currentTimeMillis() + wallMs;
        // 无进展守卫：正常 DAG 每节点最多推进 2 拍（执行 + 跳过判定），这里留足冗余
        int maxSteps = Math.max(runtime.nodes().size() * 2 + 8, 16);
        int steps = 0;

        // 埋点：首次运行在这里建根 trace，HUMAN 恢复后按 run.trace_id 续用同一条
        traceSafely("beginRun", run.getId(), () -> traces.beginRun(run));
        try {
            while (true) {
                // 协作式取消放最前：取消接口可能在另一线程写库，这里每拍重读一次。
                // 顺序有意为之——用户主动取消优先于墙钟 / 无进展文案，避免「已取消」被误报成「撞限」。
                WorkflowRun fresh = runs.findById(run.getId()).orElse(null);
                if (fresh == null) {
                    return;
                }
                WorkflowRunStatus freshStatus = WorkflowRunStatus.parse(fresh.getStatus());
                if (freshStatus == WorkflowRunStatus.CANCELLED) {
                    run.setStatus(fresh.getStatus());
                    return;
                }
                if (++steps > maxSteps) {
                    failRun(run, "撞限：单次调度无进展（节点步数超过 " + maxSteps + "）；"
                            + progressBrief(rowByNode), operatorId);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    failRun(run, "撞限：工作流运行超出墙钟上限 " + wallMs
                            + "ms（agenthub.workflow.wall-ms）；" + progressBrief(rowByNode), operatorId);
                    return;
                }
                WorkflowNode next = pickNext(runtime, rowByNode);
                if (next == null) {
                    settle(run, runtime, rowByNode, operatorId);
                    return;
                }
                boolean keepGoing = executeNode(run, runtime, next, rowByNode, ctx, outputs, operatorId);
                if (!keepGoing) {
                    return;
                }
            }
        } finally {
            // 只有终态才收口；PAUSED_HUMAN 保持 RUNNING，等人工通过后续写
            traceSafely("finishRun", run.getId(), () -> traces.finishRun(run));
        }
    }

    /**
     * 埋点调用的安全壳：观测是旁路，写失败只告警，绝不能改变 run 的结果。
     *
     * @param action 动作名，仅用于日志定位（beginRun / beginNode / endNode / finishRun）
     * @param runId  运行 id
     * @param task   实际埋点调用
     */
    private void traceSafely(String action, Long runId, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.warn("工作流埋点失败 action={} runId={} type={}", action, runId, ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 挑下一个可运行节点：入边全部判定且至少一条被选中。
     * <p>入边全部判定但都没选中 → 该节点 SKIPPED，继续扫描；扫描后重新来一轮，
     * 让下游节点在同一拍内获得「上游已跳过」的信息。
     */
    private WorkflowNode pickNext(WorkflowRuntimeGraph runtime, Map<String, WorkflowNodeRun> rowByNode) {
        while (true) {
            boolean skippedAny = false;
            for (WorkflowNode node : runtime.nodes()) {
                WorkflowNodeRun row = rowByNode.get(node.getId());
                if (row == null || WorkflowNodeRunStatus.parse(row.getStatus()) != WorkflowNodeRunStatus.PENDING) {
                    continue;
                }
                List<WorkflowEdge> incoming = runtime.incoming(node.getId());
                if (runtime.type(node.getId()) == WorkflowNodeType.START) {
                    return node;
                }
                if (!allDecided(incoming, rowByNode)) {
                    continue;
                }
                if (!anySelected(incoming, rowByNode)) {
                    markSkipped(row);
                    skippedAny = true;
                    continue;
                }
                return node;
            }
            if (!skippedAny) {
                return null;
            }
        }
    }

    private boolean allDecided(List<WorkflowEdge> incoming, Map<String, WorkflowNodeRun> rowByNode) {
        for (WorkflowEdge edge : incoming) {
            WorkflowNodeRun source = rowByNode.get(edge.getSource());
            if (source == null) {
                continue;
            }
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(source.getStatus());
            if (status == null || !status.terminal()) {
                return false;
            }
        }
        return true;
    }

    private boolean anySelected(List<WorkflowEdge> incoming, Map<String, WorkflowNodeRun> rowByNode) {
        for (WorkflowEdge edge : incoming) {
            WorkflowNodeRun source = rowByNode.get(edge.getSource());
            if (source != null && selectedEdgeIds(source).contains(edge.getId())) {
                return true;
            }
        }
        return false;
    }

    private void markSkipped(WorkflowNodeRun row) {
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.SKIPPED).name());
        row.setEndTime(LocalDateTime.now());
        nodeRuns.update(row);
    }

    /**
     * 执行单个节点。
     *
     * @return true 表示可继续调度；false 表示已停在 HUMAN 或 run 已失败 / 取消
     */
    private boolean executeNode(WorkflowRun run, WorkflowRuntimeGraph runtime, WorkflowNode node,
                                Map<String, WorkflowNodeRun> rowByNode, ObjectNode ctx,
                                ObjectNode outputs, long operatorId) {
        WorkflowNodeRun row = rowByNode.get(node.getId());
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.RUNNING).name());
        row.setStartTime(LocalDateTime.now());
        nodeRuns.update(row);

        WorkflowNodeType type = runtime.type(node.getId());
        // 埋点：节点步在这里开，收口统一放 finally（成功 / 失败 / 取消都落终态）
        traceSafely("beginNode", run.getId(), () -> traces.beginNode(run, node));
        try {
            switch (type) {
                case START -> {
                    row.setInputJson(inputOf(ctx, null).toString());
                    row.setOutputJson(ctx.get("input") == null
                            ? json.createObjectNode().toString()
                            : ctx.get("input").toString());
                    succeed(row, node, outputs, defaultEdges(runtime, node), ctx);
                }
                case END -> {
                    row.setInputJson(inputOf(ctx, node.getConfig()).toString());
                    ObjectNode result = WorkflowNodeInputs.mapped(ctx,
                            WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
                    if (result.isEmpty()) {
                        // 没配映射就给节点编码＋输入快照，保证 run 输出不是空对象
                        result.set("nodeCode", json.getNodeFactory().textNode(node.getCode()));
                        result.set("input", ctx.get("input"));
                    }
                    row.setOutputJson(result.toString());
                    succeed(row, node, outputs, List.of(), ctx);
                }
                case CONDITION -> {
                    row.setInputJson(inputOf(ctx, node.getConfig()).toString());
                    List<WorkflowEdge> chosen = chooseConditionEdge(runtime, node, ctx);
                    if (chosen.isEmpty()) {
                        return handleFailure(run, runtime, node, row,
                                "条件「" + node.getCode() + "」所有 COND 都为假且没有 DEFAULT 出口", null, operatorId);
                    }
                    ObjectNode probe = json.createObjectNode();
                    probe.put("matchedEdgeId", chosen.get(0).getId());
                    row.setOutputJson(probe.toString());
                    succeed(row, node, outputs, chosen, ctx);
                }
                case PARALLEL_FORK -> {
                    // 并行硬顶：分支数按非 ERROR 出边计；超额直接失败该 FORK，
                    // 错误边可接管，否则整 run FAILED（比「静默排队」更可预期、可测）
                    int branches = defaultEdges(runtime, node).size();
                    int limit = properties.effectiveMaxParallelBranches();
                    if (branches > limit) {
                        return handleFailure(run, runtime, node, row,
                                "并行分叉「" + node.getCode() + "」有 " + branches
                                        + " 条分支，超过上限 " + limit
                                        + "（agenthub.workflow.max-parallel-branches）；已拒绝本次扇出",
                                null, operatorId);
                    }
                    row.setInputJson(inputOf(ctx, node.getConfig()).toString());
                    row.setOutputJson(json.createObjectNode().toString());
                    succeed(row, node, outputs, defaultEdges(runtime, node), ctx);
                }
                case PARALLEL_JOIN -> {
                    row.setInputJson(inputOf(ctx, node.getConfig()).toString());
                    row.setOutputJson(json.createObjectNode().toString());
                    succeed(row, node, outputs, defaultEdges(runtime, node), ctx);
                }
                case AGENT -> {
                    if (!invokeAgent(run, runtime, node, row, ctx, outputs, operatorId)) {
                        return false;
                    }
                }
                case TOOL -> {
                    if (!invokeTool(run, runtime, node, row, ctx, outputs, operatorId)) {
                        return false;
                    }
                }
                case HUMAN -> {
                    return enterHuman(run, node, row, ctx, operatorId);
                }
                default -> {
                    // 校验器本应拦下 P1 类型；手改库绕过后在这里明确失败，不静默跳过
                    return handleFailure(run, runtime, node, row,
                            "节点类型 " + type + " 尚未接入引擎（P1）", null, operatorId);
                }
            }
            return true;
        } catch (WorkflowNodeFailure ex) {
            return handleFailure(run, runtime, node, row, ex.getMessage(), ex, operatorId);
        } catch (BizException ex) {
            return handleFailure(run, runtime, node, row, ex.getMessage(), ex, operatorId);
        } catch (RuntimeException ex) {
            log.warn("工作流节点执行异常 runId={} nodeCode={} type={} root={}",
                    run.getId(), node.getCode(), type, ex.getClass().getSimpleName(), ex);
            return handleFailure(run, runtime, node, row,
                    "节点「" + node.getCode() + "」执行异常：" + safe(ex.getMessage()), ex, operatorId);
        } finally {
            // 哪怕上面已 return，也要把节点步收成终态；WAITING_HUMAN 由根 trace 统一收口
            traceSafely("endNode", run.getId(), () -> traces.endNode(run, node, row));
        }
    }

    private boolean invokeAgent(WorkflowRun run, WorkflowRuntimeGraph runtime, WorkflowNode node,
                                WorkflowNodeRun row, ObjectNode ctx, ObjectNode outputs, long operatorId) {
        Long agentId = WorkflowNodeConfig.longValue(node.getConfig(), "agentId");
        if (agentId == null) {
            return handleFailure(run, runtime, node, row, "Agent 节点未绑定 Agent", null, operatorId);
        }
        JsonNode mapping = WorkflowNodeConfig.objectValue(node.getConfig(), "inputMapping");
        String message = WorkflowNodeInputs.message(ctx, mapping);
        ObjectNode mapped = WorkflowNodeInputs.mapped(ctx, mapping);
        row.setInputJson(mapped.isEmpty()
                ? json.createObjectNode().put("text", message).toString()
                : mapped.toString());
        long userId = run.getTriggeredBy() == null ? operatorId : run.getTriggeredBy();
        String text = agentInvoker.invoke(agentId, message, userId, run.getId());
        if (text == null || text.isBlank()) {
            return handleFailure(run, runtime, node, row,
                    "Agent「" + node.getCode() + "」没有返回文本", null, operatorId);
        }
        ObjectNode raw = json.createObjectNode();
        raw.put("text", text);
        ObjectNode shaped = WorkflowNodeInputs.shapeOutput(raw,
                WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
        row.setOutputJson(shaped.toString());
        succeed(row, node, outputs, defaultEdges(runtime, node), ctx);
        return true;
    }

    private boolean invokeTool(WorkflowRun run, WorkflowRuntimeGraph runtime, WorkflowNode node,
                               WorkflowNodeRun row, ObjectNode ctx, ObjectNode outputs, long operatorId) {
        Long toolId = WorkflowNodeConfig.longValue(node.getConfig(), "toolId");
        if (toolId == null) {
            return handleFailure(run, runtime, node, row, "工具节点未绑定工具项", null, operatorId);
        }
        ObjectNode args = WorkflowNodeInputs.mapped(ctx,
                WorkflowNodeConfig.objectValue(node.getConfig(), "inputMapping"));
        row.setInputJson(args.toString());
        String text = toolInvoker.invoke(toolId, args);
        if (text == null || text.isBlank()) {
            return handleFailure(run, runtime, node, row,
                    "工具「" + node.getCode() + "」没有返回内容", null, operatorId);
        }
        ObjectNode raw = json.createObjectNode();
        raw.put("text", text);
        ObjectNode shaped = WorkflowNodeInputs.shapeOutput(raw,
                WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
        row.setOutputJson(shaped.toString());
        succeed(row, node, outputs, defaultEdges(runtime, node), ctx);
        return true;
    }

    /**
     * 进入人工卡点：节点 WAITING_HUMAN、run PAUSED_HUMAN、写 OPEN 待办，然后停止调度。
     */
    private boolean enterHuman(WorkflowRun run, WorkflowNode node, WorkflowNodeRun row,
                               ObjectNode ctx, long operatorId) {
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.WAITING_HUMAN).name());
        nodeRuns.update(row);

        HumanTimeoutPolicy policy = HumanTimeoutPolicy.parse(
                WorkflowNodeConfig.textValue(node.getConfig(), "timeoutPolicy"));
        if (policy == null) {
            // 校验器已拦；手改库绕过时按最安全的自动拒绝兜底
            policy = HumanTimeoutPolicy.AUTO_REJECT;
        }
        long timeoutMs = WorkflowNodeConfig.longValue(node.getConfig(), "timeoutMs") == null
                ? properties.getHumanDefaultTimeoutMs()
                : Math.max(WorkflowNodeConfig.longValue(node.getConfig(), "timeoutMs"), 0L);
        LocalDateTime expire = timeoutMs <= 0 ? null : LocalDateTime.now().plusNanos(timeoutMs * 1_000_000L);

        WorkflowHumanTask task = new WorkflowHumanTask();
        task.setTenantId(run.getTenantId());
        task.setRunId(run.getId());
        task.setNodeRunId(row.getId());
        task.setNodeCode(node.getCode());
        task.setStatus("OPEN");
        task.setAssigneeRule(WorkflowAssigneeRule.normalize(
                WorkflowNodeConfig.textValue(node.getConfig(), "assigneeRule")));
        task.setTimeoutPolicy(policy.name());
        ObjectNode form = json.createObjectNode();
        form.set("input", ctx.get("input"));
        task.setFormJson(form.toString());
        task.setExpireTime(expire);
        task.setCreatedBy(operatorId);
        humanTasks.insert(task);

        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.PAUSED_HUMAN,
                null, null, operatorId);
        return false;
    }

    /**
     * 节点失败收口：有 ERROR 出边则选中错误边继续；否则整 run FAILED。
     *
     * @return true 表示错误边已接管，可继续调度
     */
    private boolean handleFailure(WorkflowRun run, WorkflowRuntimeGraph runtime, WorkflowNode node,
                                  WorkflowNodeRun row, String message, Throwable cause, long operatorId) {
        List<WorkflowEdge> errorEdges = runtime == null ? List.of() : runtime.errorOutgoing(node.getId());
        row.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.parse(row.getStatus()), WorkflowNodeRunStatus.FAILED).name());
        row.setErrorSummary(truncate(message));
        row.setEndTime(LocalDateTime.now());
        row.setSelectedEdges(joinIds(errorEdges));
        nodeRuns.update(row);
        if (cause != null) {
            log.warn("工作流节点失败 runId={} nodeCode={} reason={} root={}",
                    run.getId(), node.getCode(), message,
                    cause.getClass().getSimpleName());
        }
        if (!errorEdges.isEmpty()) {
            return true;
        }
        failRun(run, "节点「" + node.getCode() + "」失败：" + message, operatorId);
        return false;
    }

    /**
     * CONDITION 选路：按图内顺序求值 COND 边，第一条为真即走；都不为真走 DEFAULT。
     */
    private List<WorkflowEdge> chooseConditionEdge(WorkflowRuntimeGraph runtime, WorkflowNode node,
                                                   ObjectNode ctx) {
        WorkflowEdge fallback = null;
        for (WorkflowEdge edge : runtime.outgoing(node.getId())) {
            WorkflowEdgeType type = WorkflowEdgeType.parseOrDefault(edge.getType());
            if (type == WorkflowEdgeType.DEFAULT) {
                fallback = edge;
                continue;
            }
            if (type != WorkflowEdgeType.COND) {
                continue;
            }
            JsonNode rule = conditionOf(edge);
            if (rule == null) {
                continue;
            }
            if (JsonLogicEvaluator.test(rule, ctx)) {
                return List.of(edge);
            }
        }
        return fallback == null ? List.of() : List.of(fallback);
    }

    /**
     * 条件既可能在边上 config.condition，也兼容刀1 前端只写 label 的历史数据；
     * 后者没有表达式，按「不成立」处理，由 DEFAULT 兜底。
     */
    private JsonNode conditionOf(WorkflowEdge edge) {
        if (edge.getConfig() == null || !edge.getConfig().isObject()) {
            return null;
        }
        JsonNode rule = edge.getConfig().get("condition");
        return rule == null || rule.isNull() ? null : rule;
    }

    /**
     * 普通成功节点的出边：除 ERROR 外全部选中（条件分支的选路由 CONDITION 自己决定）。
     */
    private List<WorkflowEdge> defaultEdges(WorkflowRuntimeGraph runtime, WorkflowNode node) {
        List<WorkflowEdge> edges = new ArrayList<>();
        for (WorkflowEdge edge : runtime.outgoing(node.getId())) {
            if (WorkflowEdgeType.parseOrDefault(edge.getType()) != WorkflowEdgeType.ERROR) {
                edges.add(edge);
            }
        }
        return edges;
    }

    /**
     * 普通节点成功收口：写状态、输出、选中边，并把产出放进 ctx.outputs.&lt;code&gt;。
     */
    private void succeed(WorkflowNodeRun row, WorkflowNode node, ObjectNode outputs,
                         List<WorkflowEdge> selected, ObjectNode ctx) {
        String code = node.getCode();
        JsonNode produced = parse(row.getOutputJson());
        // 并行分支写同名 key：先判冲突再落 SUCCESS，避免「行成功但 run 失败」的半截状态
        if (code != null && !code.isBlank() && produced != null
                && outputs.has(code) && !outputs.get(code).equals(produced)) {
            throw new WorkflowNodeFailure("节点编码「" + code + "」重复写入 outputs，请检查是否有同名节点");
        }
        row.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.parse(row.getStatus()), WorkflowNodeRunStatus.SUCCESS).name());
        row.setEndTime(LocalDateTime.now());
        row.setSelectedEdges(joinIds(selected));
        nodeRuns.update(row);
        if (code == null || code.isBlank() || produced == null) {
            return;
        }
        outputs.set(code, produced);
    }

    /**
     * 调度收尾：全部节点终态后判定 SUCCESS / FAILED。
     */
    private void settle(WorkflowRun run, WorkflowRuntimeGraph runtime,
                        Map<String, WorkflowNodeRun> rowByNode, long operatorId) {
        boolean waitingHuman = false;
        boolean failed = false;
        boolean endSuccess = false;
        String firstError = null;
        ObjectNode endOutput = json.createObjectNode();
        for (WorkflowNode node : runtime.nodes()) {
            WorkflowNodeRun row = rowByNode.get(node.getId());
            if (row == null) {
                continue;
            }
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(row.getStatus());
            if (status == WorkflowNodeRunStatus.WAITING_HUMAN) {
                waitingHuman = true;
            }
            if (status == WorkflowNodeRunStatus.FAILED) {
                failed = true;
                if (firstError == null) {
                    firstError = row.getErrorSummary();
                }
            }
            if (status == WorkflowNodeRunStatus.SUCCESS && runtime.type(node.getId()) == WorkflowNodeType.END) {
                endSuccess = true;
                JsonNode produced = parse(row.getOutputJson());
                if (produced != null) {
                    endOutput.set(node.getCode(), produced);
                }
            }
        }
        if (waitingHuman) {
            transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.PAUSED_HUMAN,
                    null, null, operatorId);
            return;
        }
        if (endSuccess) {
            // 走到 END 即成功；途中有被 ERROR 边接管的失败节点也允许成功
            transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.SUCCESS,
                    null, endOutput.toString(), operatorId);
            return;
        }
        String reason = failed
                ? "工作流失败：" + (firstError == null ? "节点执行失败" : firstError)
                : "未到达结束节点：可能存在未连通的节点或条件分支全部未选中";
        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.FAILED,
                truncate(reason), null, operatorId);
    }

    private void failRun(WorkflowRun run, String reason, long operatorId) {
        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.FAILED,
                truncate(reason), null, operatorId);
    }

    private void transitionRun(WorkflowRun run, WorkflowRunStatus from, WorkflowRunStatus to,
                               String errorSummary, String outputJson, long operatorId) {
        WorkflowStateMachine.requireRun(from, to);
        run.setStatus(to.name());
        if (errorSummary != null) {
            run.setErrorSummary(errorSummary);
        }
        if (outputJson != null) {
            run.setOutputJson(outputJson);
        }
        LocalDateTime end = to.terminal() ? LocalDateTime.now() : null;
        if (end != null) {
            run.setEndTime(end);
        }
        runs.updateStatus(run.getId(), to.name(), errorSummary, outputJson, end, operatorId);
    }

    /**
     * 取回 / 自愈节点运行态：建 run 时已批量预建；缺行（历史数据或人工补录）按图补 PENDING。
     */
    private List<WorkflowNodeRun> ensureNodeRuns(WorkflowRun run, WorkflowRuntimeGraph runtime) {
        List<WorkflowNodeRun> rows = nodeRuns.listByRunId(run.getId());
        Map<String, WorkflowNodeRun> byNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : rows) {
            byNode.put(row.getNodeId(), row);
        }
        List<WorkflowNodeRun> missing = new ArrayList<>();
        for (WorkflowNode node : runtime.nodes()) {
            if (byNode.containsKey(node.getId())) {
                continue;
            }
            WorkflowNodeRun row = new WorkflowNodeRun();
            row.setRunId(run.getId());
            row.setNodeId(node.getId());
            row.setNodeCode(node.getCode());
            row.setNodeType(node.getType());
            row.setAttempt(ATTEMPT);
            row.setStatus(WorkflowNodeRunStatus.PENDING.name());
            missing.add(row);
        }
        if (!missing.isEmpty()) {
            nodeRuns.insertBatch(missing);
            for (WorkflowNodeRun row : missing) {
                byNode.put(row.getNodeId(), row);
            }
        }
        // 按图顺序返回，画布高亮与排障都依赖这个顺序
        List<WorkflowNodeRun> ordered = new ArrayList<>();
        for (WorkflowNode node : runtime.nodes()) {
            WorkflowNodeRun row = byNode.get(node.getId());
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    private ObjectNode collectOutputs(WorkflowRuntimeGraph runtime, List<WorkflowNodeRun> rows) {
        ObjectNode outputs = json.createObjectNode();
        Map<String, String> codeById = new LinkedHashMap<>();
        for (WorkflowNode node : runtime.nodes()) {
            codeById.put(node.getId(), node.getCode());
        }
        for (WorkflowNodeRun row : rows) {
            if (WorkflowNodeRunStatus.parse(row.getStatus()) != WorkflowNodeRunStatus.SUCCESS) {
                continue;
            }
            String code = codeById.get(row.getNodeId());
            JsonNode produced = parse(row.getOutputJson());
            if (code != null && !code.isBlank() && produced != null) {
                outputs.set(code, produced);
            }
        }
        return outputs;
    }

    private ObjectNode inputOf(ObjectNode ctx, JsonNode config) {
        ObjectNode mapped = WorkflowNodeInputs.mapped(ctx,
                WorkflowNodeConfig.objectValue(config, "inputMapping"));
        if (!mapped.isEmpty()) {
            return mapped;
        }
        JsonNode input = ctx.get("input");
        if (input instanceof ObjectNode object) {
            return object.deepCopy();
        }
        ObjectNode wrapper = json.createObjectNode();
        wrapper.set("input", input);
        return wrapper;
    }

    private Set<String> selectedEdgeIds(WorkflowNodeRun row) {
        Set<String> ids = new LinkedHashSet<>();
        if (row.getSelectedEdges() == null || row.getSelectedEdges().isBlank()) {
            return ids;
        }
        for (String part : row.getSelectedEdges().split(",")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        return ids;
    }

    private String joinIds(List<WorkflowEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return "";
        }
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

    private ObjectNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        JsonNode node = parse(raw);
        return node != null && node.isObject() ? (ObjectNode) node : json.createObjectNode();
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 调度进度摘要（脱敏）：只给状态计数与已完成节点编码，不带任何输入 / 输出正文。
     *
     * <p>用于撞限类失败文案，让用户知道「跑到哪一步卡住」而不是只看到超时数字。
     *
     * @param rowByNode 当前 run 的节点运行态
     * @return 中文摘要，如「已完成 2 个节点（start、answer），失败 0，等待 0，共 5 个」
     */
    private String progressBrief(Map<String, WorkflowNodeRun> rowByNode) {
        int success = 0;
        int failed = 0;
        int waiting = 0;
        List<String> doneCodes = new ArrayList<>();
        for (WorkflowNodeRun row : rowByNode.values()) {
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(row.getStatus());
            if (status == WorkflowNodeRunStatus.SUCCESS) {
                success++;
                if (doneCodes.size() < 5) {
                    doneCodes.add(row.getNodeCode());
                }
            } else if (status == WorkflowNodeRunStatus.FAILED) {
                failed++;
            } else if (status == WorkflowNodeRunStatus.WAITING_HUMAN) {
                waiting++;
            }
        }
        String codes = doneCodes.isEmpty() ? "无" : String.join("、", doneCodes);
        return "已完成 " + success + " 个节点（" + codes + "），失败 " + failed
                + "，等待人工 " + waiting + "，共 " + rowByNode.size() + " 个";
    }

    /**
     * 节点错误摘要：500 字上限，null 保持 null。实现见 {@link TextClip#raw(String, int)}。
     *
     * @param text 原始原因（可能为空）
     * @return 不超过 500 字的原文前缀
     */
    private String truncate(String text) {
        return TextClip.raw(text, 500);
    }

    private String safe(String text) {
        return text == null || text.isBlank() ? "未知错误" : text;
    }
}
