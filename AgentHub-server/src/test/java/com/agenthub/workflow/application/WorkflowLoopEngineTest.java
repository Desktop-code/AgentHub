package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 循环节点调度单测。所属域：workflow 的 application。
 *
 * <p>只覆盖声明循环：次数、平台硬顶、超限出路、取消 / 失败 / 超时、打回反馈、嵌套计数、
 * 同一节点多轮不覆盖。不出站、不连库。
 */
class WorkflowLoopEngineTest {

    private InMemoryWorkflowStore store;
    private WorkflowEngine engine;
    private WorkflowProperties properties;
    private List<String> agentCalls;
    private boolean cancelAfterFirst;
    private boolean failOnSecond;
    /** 超时用例让端口真实耗时，单位毫秒 */
    private long sleepMs;
    /** 非空时 AGENT 桩固定返回这段文本，用于验证条件分支 */
    private String fixedAgentReply;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        properties = new WorkflowProperties();
        properties.setTrialWallMs(60_000L);
        agentCalls = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                (agentId, message, userId, runId) -> {
                    if (failOnSecond && !agentCalls.isEmpty()) {
                        throw new WorkflowNodeFailure("mock：循环内节点失败");
                    }
                    agentCalls.add(agentId + ":" + message);
                    if (cancelAfterFirst) {
                        store.run(runId).setStatus(WorkflowRunStatus.CANCELLED.name());
                    }
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return fixedAgentReply == null ? "答：" + message : fixedAgentReply;
                },
                (toolId, args) -> "工具结果",
                new WorkflowGraphCodec(json), properties, json, new RecordingTraceWriter());
    }

    @Test
    @DisplayName("声明循环按配置次数进入循环体，然后走下游结束")
    void declaredLoopRunsConfiguredTimesThenExits() {
        WorkflowRun run = start(simpleLoop(3, "DOWNSTREAM", true), "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).containsExactly("101:1", "101:2", "101:3");
        List<WorkflowNodeRun> rounds = rows(run.getId(), "rewrite");
        assertThat(rounds).hasSize(3);
        assertThat(rounds).allMatch(row -> WorkflowNodeRunStatus.SUCCESS.name().equals(row.getStatus()));
        assertThat(rounds.get(0).getOutputJson()).contains("答：1");
        assertThat(rounds.get(2).getOutputJson()).contains("答：3");
        assertThat(rows(run.getId(), "revise")).hasSize(4);
        assertThat(rows(run.getId(), "revise").get(3).getOutputJson()).contains("\"decision\":\"EXIT\"");
    }

    @Test
    @DisplayName("图上写得再大，运行也停在平台硬顶")
    void platformCapStopsBeforeGraphCount() {
        properties.setMaxLoopIterations(2);
        WorkflowRun lowered = start(simpleLoop(9, "DOWNSTREAM", true), "{}");
        assertThat(store.run(lowered.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).containsExactly("101:1", "101:2");

        agentCalls.clear();
        properties.setMaxLoopIterations(100);
        WorkflowRun absolute = start(simpleLoop(100, "DOWNSTREAM", true), "{}");
        assertThat(store.run(absolute.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).hasSize(WorkflowProperties.MAX_LOOP_ITERATIONS_CAP);
    }

    @Test
    @DisplayName("取消、节点失败、整段超时都结束循环，不再进入下一轮")
    void cancelFailureAndTimeoutStopTheLoop() {
        cancelAfterFirst = true;
        WorkflowRun cancelled = start(simpleLoop(5, "DOWNSTREAM", true), "{}");
        assertThat(store.run(cancelled.getId()).getStatus()).isEqualTo(WorkflowRunStatus.CANCELLED.name());
        assertThat(agentCalls).containsExactly("101:1");

        cancelAfterFirst = false;
        failOnSecond = true;
        agentCalls.clear();
        WorkflowRun failed = start(simpleLoop(5, "DOWNSTREAM", true), "{}");
        assertThat(store.run(failed.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(agentCalls).containsExactly("101:1");
        assertThat(rows(failed.getId(), "rewrite")).noneMatch(row ->
                row.getAttempt() != null && row.getAttempt() > 2
                        && WorkflowNodeRunStatus.SUCCESS.name().equals(row.getStatus()));

        failOnSecond = false;
        agentCalls.clear();
        properties.setWallMs(1_000L);
        long previousSleep = 0L;
        try {
            setSleep(1_100L);
            WorkflowRun timedOut = start(simpleLoop(5, "DOWNSTREAM", true), "{}");
            assertThat(store.run(timedOut.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
            assertThat(store.run(timedOut.getId()).getErrorSummary()).isEqualTo("工作流运行超时，已停止");
            assertThat(agentCalls).containsExactly("101:1");
        } finally {
            setSleep(previousSleep);
            properties.setWallMs(0L);
        }
    }

    @Test
    @DisplayName("节点失败若有失败边，走失败边结束，不再回到循环")
    void failureWithErrorEdgeDoesNotLoopAgain() {
        failOnSecond = true;
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + loopNode("n2", "revise", 4, "DOWNSTREAM") + ","
                        + WorkflowTestGraphs.node("n3", "rewrite", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("n4", "end", "END", "{}") + ","
                        + WorkflowTestGraphs.node("n5", "errEnd", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n2", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n2", "n4", "LOOP_EXIT", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n3", "n5", "ERROR", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).containsExactly("101:1");
        assertThat(rows(run.getId(), "errEnd")).anyMatch(row ->
                WorkflowNodeRunStatus.SUCCESS.name().equals(row.getStatus()));
    }

    @Test
    @DisplayName("打回反馈和迭代序号进入下一轮，循环外选中的语言还在")
    void feedbackIterationAndOutsideVariableSurvive() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("nAsk", "ask", "AGENT",
                        "{\"agentId\":11,\"inputMapping\":{\"message\":\"$.input.lang\"}}") + ","
                        + loopNode("n2", "revise", 2, "DOWNSTREAM").replace(
                        "}", ",\"feedbackPath\":\"$.outputs.rewrite\"}") + ","
                        + WorkflowTestGraphs.node("n3", "rewrite", "AGENT",
                        "{\"agentId\":12,\"inputMapping\":{\"lang\":\"$.outputs.ask.text\","
                                + "\"round\":\"$.vars.loop.iteration\","
                                + "\"feedback\":\"$.vars.loop.feedback.text\"}}") + ","
                        + WorkflowTestGraphs.node("n4", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "nAsk", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "nAsk", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n2", "n3", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n2", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n2", "n4", "LOOP_EXIT", null));

        WorkflowRun run = start(graph, "{\"lang\":\"zh\"}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        List<String> rewrites = agentCalls.stream().filter(call -> call.startsWith("12:")).toList();
        assertThat(rewrites).hasSize(2);
        assertThat(rewrites.get(0)).contains("答：zh").contains("\"round\":1");
        assertThat(rewrites.get(1)).contains("答：zh").contains("\"round\":2").contains("\\\"round\\\":1");
        List<WorkflowNodeRun> rounds = rows(run.getId(), "rewrite");
        assertThat(rounds.get(0).getOutputJson()).isNotEqualTo(rounds.get(1).getOutputJson());
        assertThat(rounds.get(0).getOutputJson()).contains("答：zh");
    }

    @Test
    @DisplayName("嵌套循环各自计数：外层两轮，内层每轮各两轮")
    void nestedLoopsCountSeparately() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + loopNode("nOuter", "outer", 2, "DOWNSTREAM") + ","
                        + WorkflowTestGraphs.node("nMid", "mid", "AGENT",
                        "{\"agentId\":1,\"inputMapping\":{\"message\":\"$.vars.loops.nOuter.iteration\"}}") + ","
                        + loopNode("nInner", "inner", 2, "DOWNSTREAM") + ","
                        + WorkflowTestGraphs.node("nLeaf", "leaf", "AGENT",
                        "{\"agentId\":2,\"inputMapping\":{\"message\":\"$.vars.loops.nInner.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("nTail", "tail", "AGENT", "{\"agentId\":3}") + ","
                        + WorkflowTestGraphs.node("nEnd", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "nOuter", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "nOuter", "nMid", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e3", "nMid", "nInner", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e4", "nInner", "nLeaf", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e5", "nLeaf", "nInner", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e6", "nInner", "nTail", "LOOP_EXIT", null) + ","
                        + WorkflowTestGraphs.edge("e7", "nTail", "nOuter", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e8", "nOuter", "nEnd", "LOOP_EXIT", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(callsOf(1)).containsExactly("1:1", "1:2");
        assertThat(callsOf(2)).containsExactly("2:1", "2:2", "2:1", "2:2");
    }

    @Test
    @DisplayName("循环体里的并行分叉和汇合按轮成对，不重复汇合")
    void loopBodyForkJoinsOncePerIteration() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + loopNode("n2", "revise", 2, "DOWNSTREAM") + ","
                        + WorkflowTestGraphs.node("n3", "fork", "PARALLEL_FORK", "{\"forkGroupId\":\"g1\"}") + ","
                        + WorkflowTestGraphs.node("n4", "left", "AGENT", "{\"agentId\":11}") + ","
                        + WorkflowTestGraphs.node("n5", "right", "AGENT", "{\"agentId\":12}") + ","
                        + WorkflowTestGraphs.node("n6", "join", "PARALLEL_JOIN", "{\"forkGroupId\":\"g1\"}") + ","
                        + WorkflowTestGraphs.node("n7", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n5", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n6", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e6", "n5", "n6", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e7", "n6", "n2", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e8", "n2", "n7", "LOOP_EXIT", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(callsOf(11)).hasSize(2);
        assertThat(callsOf(12)).hasSize(2);
        assertThat(rows(run.getId(), "join"))
                .filteredOn(row -> WorkflowNodeRunStatus.SUCCESS.name().equals(row.getStatus()))
                .hasSize(2);
    }

    @Test
    @DisplayName("次数用尽走转人工或失败，不把超限当成节点异常继续转")
    void exceededOutcomeIsHumanOrFail() {
        String humanGraph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + loopNode("n2", "revise", 1, "HUMAN") + ","
                        + WorkflowTestGraphs.node("n3", "rewrite", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("n4", "review", "HUMAN",
                        "{\"timeoutPolicy\":\"AUTO_REJECT\"}") + ","
                        + WorkflowTestGraphs.node("n5", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n2", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n2", "n4", "LOOP_EXIT", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n5", "NORMAL", null));
        WorkflowRun paused = start(humanGraph, "{}");
        assertThat(store.run(paused.getId()).getStatus()).isEqualTo(WorkflowRunStatus.PAUSED_HUMAN.name());
        assertThat(agentCalls).containsExactly("101:1");
        assertThat(rows(paused.getId(), "review")).anyMatch(row ->
                WorkflowNodeRunStatus.WAITING_HUMAN.name().equals(row.getStatus()));

        agentCalls.clear();
        String failGraph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + loopNode("n2", "revise", 1, "FAIL") + ","
                        + WorkflowTestGraphs.node("n3", "rewrite", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("n4", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n2", "LOOP_BACK", null));
        WorkflowRun failed = start(failGraph, "{}");
        assertThat(store.run(failed.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(failed.getId()).getErrorSummary()).contains("上限");
        assertThat(agentCalls).containsExactly("101:1");
    }

    @Test
    @DisplayName("打完最后一轮仍不通过时，次数用尽与「通过」共用下游也能到结束")
    void exhaustedExitReachesSharedDownstream() {
        fixedAgentReply = "结论：有问题";
        String graph = sharedDownstreamGraph();

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).containsExactly("101:1", "101:2", "101:3");
        assertThat(rows(run.getId(), "end")).anyMatch(row ->
                WorkflowNodeRunStatus.SUCCESS.name().equals(row.getStatus()));
    }

    @Test
    @DisplayName("条件「通过」与循环超限出口共用下游：通过时直接到结束，不被尚未定下来的出口挡住")
    void passEdgeReachesSharedDownstreamBeforeExit() {
        fixedAgentReply = "结论：通过";
        String graph = sharedDownstreamGraph();

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).containsExactly("101:1");
        assertThat(rows(run.getId(), "end")).anyMatch(row ->
                WorkflowNodeRunStatus.SUCCESS.name().equals(row.getStatus()));
    }

    /**
     * 「通过」与「次数用尽」都指向同一个结束节点的图。
     *
     * @return 图 JSON
     */
    private static String sharedDownstreamGraph() {
        String passRule = "{\"condition\":{\"in\":[\"结论：通过\",{\"var\":\"outputs.check.text\"}]}}";
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + loopNode("n2", "revise", 3, "DOWNSTREAM") + ","
                        + WorkflowTestGraphs.node("n3", "check", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("n4", "cond", "CONDITION", "{}") + ","
                        + WorkflowTestGraphs.node("n5", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n4", "n5", "COND", passRule) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n2", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e6", "n2", "n5", "LOOP_EXIT", null));
    }

    /**
     * @param max        图上填写的次数
     * @param onExceeded 超限出路
     * @param withExit   是否画离开边
     * @return 图 JSON
     */
    private static String simpleLoop(int max, String onExceeded, boolean withExit) {
        String nodes = WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                + loopNode("n2", "revise", max, onExceeded) + ","
                + WorkflowTestGraphs.node("n3", "rewrite", "AGENT",
                "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}")
                + (withExit ? "," + WorkflowTestGraphs.node("n4", "end", "END", "{}") : "");
        String edges = WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                + WorkflowTestGraphs.edge("e2", "n2", "n3", "LOOP_BODY", null) + ","
                + WorkflowTestGraphs.edge("e3", "n3", "n2", "LOOP_BACK", null)
                + (withExit ? "," + WorkflowTestGraphs.edge("e4", "n2", "n4", "LOOP_EXIT", null) : "");
        return WorkflowTestGraphs.graph(nodes, edges);
    }

    private static String loopNode(String id, String code, int max, String onExceeded) {
        return WorkflowTestGraphs.node(id, code, "LOOP",
                "{\"maxIterations\":" + max + ",\"onExceeded\":\"" + onExceeded + "\"}");
    }

    private WorkflowRun start(String graphJson, String inputJson) {
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(1L);
        run.setWorkflowId(10L);
        run.setReleaseId(20L);
        run.setReleaseVersion(1);
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(7L);
        WorkflowTestGraphs.run(run, graphJson, inputJson);
        Long id = store.insertRun(run);
        engine.execute(run, 7L);
        return store.run(id);
    }

    private List<WorkflowNodeRun> rows(Long runId, String code) {
        return store.listByRunId(runId).stream()
                .filter(row -> code.equals(row.getNodeCode()))
                .toList();
    }

    private List<String> callsOf(long agentId) {
        String prefix = agentId + ":";
        return agentCalls.stream().filter(call -> call.startsWith(prefix)).toList();
    }

    private void setSleep(long millis) {
        sleepMs = millis;
    }
}
