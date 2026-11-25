package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.service.WorkflowLoopBodies;
import com.agenthub.workflow.domain.service.WorkflowLoopPolicy;
import com.agenthub.workflow.domain.service.WorkflowLoopPolicy.OnExceeded;
import com.agenthub.workflow.domain.service.WorkflowNodeInputs;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 循环节点的调度。所属域：workflow 的 application。
 *
 * <p>引擎只在三个时机调用：进入循环节点、任一节点成功之后、以及整次调度开始时恢复上下文。
 * 次数加在循环体上，不含循环外节点。达到图上次数或平台硬顶（先到为准）后走超限出路，
 * 不再进入循环体。取消、失败、时限由引擎原有收口处理，本类不把它们改写成超限出路。
 */
public final class WorkflowLoopScheduler {

    private final ObjectMapper json;
    private final WorkflowContextSupport context;
    private final WorkflowProperties properties;
    private final WorkflowLoopAttempts attempts;

    /**
     * @param json       组装循环产出与上下文
     * @param context    读取节点入参
     * @param nodeRuns   新一轮运行态的落库
     * @param properties 平台次数硬顶与单次运行条数硬顶
     */
    public WorkflowLoopScheduler(ObjectMapper json, WorkflowContextSupport context,
                                 WorkflowNodeRunRepository nodeRuns, WorkflowProperties properties) {
        this.json = json;
        this.context = context;
        this.properties = properties;
        this.attempts = new WorkflowLoopAttempts(nodeRuns, properties);
    }

    /**
     * 进入循环节点：未到次数就进入循环体并记一轮，到了就走超限出路。
     *
     * @param runtime   图
     * @param node      循环节点
     * @param row       本轮运行态（已是 RUNNING）
     * @param rowByNode 最新行，供估算还要插入多少行
     * @param ctx       上下文，迭代序号和反馈写在 vars 里
     * @return 交给引擎落库的决定
     */
    public Decision enter(WorkflowRuntimeGraph runtime, WorkflowNode node, WorkflowNodeRun row,
                          Map<String, WorkflowNodeRun> rowByNode, ObjectNode ctx) {
        Integer configured = WorkflowLoopPolicy.configuredIterations(node);
        if (configured == null) {
            return Decision.fail("循环「" + node.getCode() + "」的次数未配置或不是正整数");
        }
        OnExceeded outcome = WorkflowLoopPolicy.onExceeded(node);
        if (outcome == null) {
            return Decision.fail("循环「" + node.getCode() + "」没有配置超限出路");
        }
        int max = WorkflowLoopPolicy.effectiveIterations(configured, properties.effectiveMaxLoopIterations());
        ObjectNode state = stateOf(ctx, node.getId());
        int done = state.path("iteration").asInt(0);
        String input = context.inputOf(ctx, node.getConfig()).toString();
        if (done >= max) {
            return leave(ctx, node, outcome, runtime, state, max, input);
        }
        int next = done + 1;
        if (next > 1 && attempts.exceedsCap(row.getRunId(), body(runtime, node.getId()).size() + 1)) {
            return Decision.fail("循环「" + node.getCode() + "」继续展开会超过单次运行的节点次数上限");
        }
        if (next > 1) {
            resetNested(runtime, node.getId(), ctx);
        }
        state.put("iteration", next);
        state.put("maxIterations", max);
        state.put("decision", "BODY");
        if (!state.has("feedback") || !state.get("feedback").isObject()) {
            state.set("feedback", json.createObjectNode());
        }
        vars(ctx).set("loop", state);
        return Decision.ok(input, outputOf(state, outcome).toString(), bodyEdges(runtime, node));
    }

    /**
     * 节点已经成功落库之后：回边把结构化反馈写入上下文并再打开循环节点；
     * 循环节点自己选了进入循环体、且不是第一轮时，为循环体再开一行。
     *
     * @param runtime   图
     * @param node      刚成功的节点
     * @param row       已成功的运行态
     * @param rowByNode 最新行索引
     * @param ctx       上下文（产出已写入 outputs）
     */
    public void afterNodeSuccess(WorkflowRuntimeGraph runtime, WorkflowNode node, WorkflowNodeRun row,
                                 Map<String, WorkflowNodeRun> rowByNode, ObjectNode ctx) {
        if (runtime.type(node.getId()) == WorkflowNodeType.LOOP
                && selected(row, runtime, WorkflowEdgeType.LOOP_BODY)
                && iterationOf(ctx, node.getId()) > 1) {
            attempts.reopen(runtime, body(runtime, node.getId()), rowByNode);
        }
        for (WorkflowEdge edge : runtime.outgoing(node.getId())) {
            if (!context.selectedEdgeIds(row).contains(edge.getId())) {
                continue;
            }
            if (WorkflowEdgeType.parse(edge.getType()) != WorkflowEdgeType.LOOP_BACK) {
                continue;
            }
            WorkflowNode loop = runtime.node(edge.getTarget());
            if (loop == null || runtime.type(loop.getId()) != WorkflowNodeType.LOOP) {
                continue;
            }
            rememberFeedback(ctx, loop, node, row);
            WorkflowNodeRun latest = rowByNode.get(loop.getId());
            attempts.openNext(loop, latest, rowByNode);
        }
    }

    /**
     * 调度开始时从循环节点已经落下的产出恢复迭代与反馈。
     * 停等后续跑会新建上下文，不恢复的话次数会从 0 再计。
     *
     * @param runtime 图
     * @param rows    该次运行的全部节点行（含历史轮次，按 id 升序）
     * @param ctx     本轮上下文
     */
    public void restore(WorkflowRuntimeGraph runtime, List<WorkflowNodeRun> rows, ObjectNode ctx) {
        if (rows == null) {
            return;
        }
        for (WorkflowNodeRun row : rows) {
            if (row == null || runtime.type(row.getNodeId()) != WorkflowNodeType.LOOP) {
                continue;
            }
            if (WorkflowNodeRunStatus.parse(row.getStatus()) != WorkflowNodeRunStatus.SUCCESS) {
                continue;
            }
            JsonNode output = context.parse(row.getOutputJson());
            if (output == null || !output.isObject() || !output.has("iteration")) {
                continue;
            }
            ObjectNode state = stateOf(ctx, row.getNodeId());
            state.put("iteration", output.path("iteration").asInt(0));
            state.put("maxIterations", output.path("maxIterations").asInt(0));
            state.put("decision", output.path("decision").asText(""));
            if (output.path("feedback").isObject()) {
                state.set("feedback", output.get("feedback").deepCopy());
            }
            vars(ctx).set("loop", state);
        }
    }

    /**
     * 调度步数预算。声明了循环就按次数 × 循环体放大，避免合法循环被「无进展」提前掐断。
     * 这不是次数上限：次数仍由 {@link #enter} 执行，这里只是失控时的保险丝。
     *
     * @param runtime 图
     * @return 允许的调度步数
     */
    public int stepBudget(WorkflowRuntimeGraph runtime) {
        Map<String, List<WorkflowEdge>> outgoing = outgoingOf(runtime);
        List<String> loopIds = loopIds(runtime);
        int budget = Math.max(runtime.nodes().size() * 2 + 8, 16);
        for (String loopId : loopIds) {
            WorkflowNode node = runtime.node(loopId);
            Integer configured = WorkflowLoopPolicy.configuredIterations(node);
            int requested = configured == null ? WorkflowLoopPolicy.SUGGESTED_ITERATIONS : configured;
            int iterations = WorkflowLoopPolicy.effectiveIterations(
                    requested, properties.effectiveMaxLoopIterations());
            int ancestor = 1;
            for (String container : WorkflowLoopBodies.containers(loopId, loopIds, outgoing)) {
                Integer parentConfigured = WorkflowLoopPolicy.configuredIterations(runtime.node(container));
                int parentRequested = parentConfigured == null
                        ? WorkflowLoopPolicy.SUGGESTED_ITERATIONS : parentConfigured;
                ancestor *= WorkflowLoopPolicy.effectiveIterations(
                        parentRequested, properties.effectiveMaxLoopIterations());
            }
            int bodySize = Math.max(1, WorkflowLoopBodies.of(loopId, outgoing).size());
            budget += ancestor * iterations * (bodySize + 2) * 3;
        }
        return budget;
    }

    /**
     * 到次数后离开。失败出路不选边，由引擎直接结束运行，不跟异常边。
     */
    private Decision leave(ObjectNode ctx, WorkflowNode node, OnExceeded outcome,
                           WorkflowRuntimeGraph runtime, ObjectNode state, int max, String input) {
        state.put("maxIterations", max);
        state.put("decision", outcome == OnExceeded.FAIL ? "FAIL" : "EXIT");
        // state 与 vars.loops 里是同一个对象，这里只把便利指针拨到这一层
        vars(ctx).set("loop", state);
        String output = outputOf(state, outcome).toString();
        if (outcome == OnExceeded.FAIL) {
            return Decision.fail("循环「" + node.getCode() + "」已达上限 " + max
                    + " 次，按配置结束为失败", output);
        }
        return Decision.ok(input, output, exitEdges(runtime, node));
    }

    /**
     * 把打回源的产出收成对象写进该循环的 feedback，供下一轮改写 / 检查读取。
     */
    private void rememberFeedback(ObjectNode ctx, WorkflowNode loop, WorkflowNode source,
                                  WorkflowNodeRun sourceRow) {
        ObjectNode feedback = json.createObjectNode();
        String path = WorkflowLoopPolicy.feedbackPath(loop);
        JsonNode resolved = path == null ? null : WorkflowNodeInputs.resolve(ctx, path);
        if (resolved != null && resolved.isObject()) {
            feedback = (ObjectNode) resolved.deepCopy();
        } else if (resolved != null && !resolved.isNull() && !resolved.isMissingNode()) {
            feedback.set("value", resolved.deepCopy());
        } else {
            JsonNode produced = source.getCode() == null ? null : ctx.path("outputs").path(source.getCode());
            if (produced.isObject()) {
                feedback = (ObjectNode) produced.deepCopy();
            }
        }
        feedback.put("fromNode", source.getCode() == null ? "" : source.getCode());
        feedback.put("sourceAttempt", sourceRow.getAttempt() == null ? 1 : sourceRow.getAttempt());
        ObjectNode state = stateOf(ctx, loop.getId());
        state.set("feedback", feedback);
        vars(ctx).set("loop", state);
    }

    /**
     * 外层再进一轮时，内层循环的次数清掉，避免外层次数把内层计数累加错。
     */
    private void resetNested(WorkflowRuntimeGraph runtime, String loopId, ObjectNode ctx) {
        ObjectNode loops = loops(ctx);
        for (String id : body(runtime, loopId)) {
            if (runtime.type(id) == WorkflowNodeType.LOOP) {
                loops.remove(id);
            }
        }
    }

    private ObjectNode outputOf(ObjectNode state, OnExceeded outcome) {
        ObjectNode output = state.deepCopy();
        output.put("onExceeded", outcome.name());
        return output;
    }

    private ObjectNode stateOf(ObjectNode ctx, String loopId) {
        ObjectNode loops = loops(ctx);
        JsonNode existing = loops.get(loopId);
        if (existing instanceof ObjectNode object) {
            return object;
        }
        ObjectNode created = json.createObjectNode();
        loops.set(loopId, created);
        return created;
    }

    private ObjectNode loops(ObjectNode ctx) {
        ObjectNode vars = vars(ctx);
        JsonNode loops = vars.get("loops");
        if (loops instanceof ObjectNode object) {
            return object;
        }
        ObjectNode created = json.createObjectNode();
        vars.set("loops", created);
        return created;
    }

    private ObjectNode vars(ObjectNode ctx) {
        JsonNode vars = ctx.get("vars");
        if (vars instanceof ObjectNode object) {
            return object;
        }
        ObjectNode created = json.createObjectNode();
        ctx.set("vars", created);
        return created;
    }

    private int iterationOf(ObjectNode ctx, String loopId) {
        return loops(ctx).path(loopId).path("iteration").asInt(0);
    }

    private Set<String> body(WorkflowRuntimeGraph runtime, String loopId) {
        return WorkflowLoopBodies.of(loopId, outgoingOf(runtime));
    }

    private boolean selected(WorkflowNodeRun row, WorkflowRuntimeGraph runtime, WorkflowEdgeType type) {
        for (WorkflowEdge edge : runtime.outgoing(row.getNodeId())) {
            if (WorkflowEdgeType.parse(edge.getType()) == type
                    && context.selectedEdgeIds(row).contains(edge.getId())) {
                return true;
            }
        }
        return false;
    }

    private List<WorkflowEdge> bodyEdges(WorkflowRuntimeGraph runtime, WorkflowNode node) {
        return edgesOf(runtime, node, WorkflowEdgeType.LOOP_BODY);
    }

    private List<WorkflowEdge> exitEdges(WorkflowRuntimeGraph runtime, WorkflowNode node) {
        return edgesOf(runtime, node, WorkflowEdgeType.LOOP_EXIT);
    }

    private List<WorkflowEdge> edgesOf(WorkflowRuntimeGraph runtime, WorkflowNode node, WorkflowEdgeType type) {
        List<WorkflowEdge> edges = new ArrayList<>();
        for (WorkflowEdge edge : runtime.outgoing(node.getId())) {
            if (WorkflowEdgeType.parse(edge.getType()) == type) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private Map<String, List<WorkflowEdge>> outgoingOf(WorkflowRuntimeGraph runtime) {
        Map<String, List<WorkflowEdge>> outgoing = new LinkedHashMap<>();
        for (WorkflowNode node : runtime.nodes()) {
            outgoing.put(node.getId(), runtime.outgoing(node.getId()));
        }
        return outgoing;
    }

    private List<String> loopIds(WorkflowRuntimeGraph runtime) {
        List<String> ids = new ArrayList<>();
        for (WorkflowNode node : runtime.nodes()) {
            if (runtime.type(node.getId()) == WorkflowNodeType.LOOP) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    /**
     * 循环节点一次进入的结果。失败且 {@code stopRun} 为真时引擎直接结束运行，不走异常边。
     *
     * @param inputJson     入参 JSON
     * @param outputJson    产出 JSON
     * @param selectedEdges 选中的出边
     * @param failure       失败文案；成功时为 null
     * @param stopRun       true 表示按配置结束运行，而不是节点异常
     */
    public record Decision(String inputJson, String outputJson, List<WorkflowEdge> selectedEdges,
                           String failure, boolean stopRun) {

        /**
         * @return 成功，按选出的边继续
         */
        public static Decision ok(String inputJson, String outputJson, List<WorkflowEdge> edges) {
            return new Decision(inputJson, outputJson, edges, null, false);
        }

        /**
         * @param message 给运行和循环节点的失败文案
         * @return 结束本次运行，不附带产出
         */
        public static Decision fail(String message) {
            return fail(message, null);
        }

        /**
         * @param message    失败文案
         * @param outputJson 循环节点产出；次数用尽时带上最后一轮序号和反馈
         * @return 结束本次运行
         */
        public static Decision fail(String message, String outputJson) {
            return new Decision(null, outputJson, List.of(), message, true);
        }
    }
}
