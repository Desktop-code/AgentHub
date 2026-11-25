package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAG 调度引擎单测。所属域：workflow 的 application。
 * <p>用内存仓储 + 假 AGENT / TOOL 端口，验证「状态必须落库」「条件选路」
 * 「ERROR 边接管失败」「HUMAN 暂停」四条主线，全过程不出站、不连库。
 */
class WorkflowEngineTest {

    private InMemoryWorkflowStore store;
    private WorkflowEngine engine;
    private WorkflowProperties properties;
    private ObjectMapper json;
    private List<String> agentCalls;
    private List<Long> failingAgents;
    /** 墙钟用例用：让 AGENT 端口真实耗时，单位毫秒 */
    private long agentSleepMs;
    /** 埋点替身：断言「开跑 / 每节点 / 收尾」确实被调用，不落库 */
    private RecordingTraceWriter traceWriter;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        json = new ObjectMapper();
        agentCalls = new ArrayList<>();
        failingAgents = new ArrayList<>();
        traceWriter = new RecordingTraceWriter();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        properties = new WorkflowProperties();
        properties.setTrialWallMs(5_000L);
        engine = buildEngine(traceWriter);
    }

    /**
     * 用给定埋点替身装配引擎：其它用例默认用记录替身，降级用例传「必抛异常」替身。
     *
     * @param writer 埋点端口
     * @return 引擎
     */
    private WorkflowEngine buildEngine(WorkflowTraceWriter writer) {
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        return new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                (agentId, message, userId, runId) -> {
                    if (failingAgents.contains(agentId)) {
                        throw new WorkflowNodeFailure("mock：Agent 调用失败");
                    }
                    // 墙钟用例：按需在节点内真实耗时，验证「下一拍检查 deadline」生效
                    if (agentSleepMs > 0) {
                        try {
                            Thread.sleep(agentSleepMs);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    agentCalls.add(agentId + ":" + message);
                    return "答：" + message;
                },
                (toolId, args) -> "工具结果 " + (args == null ? "{}" : args.toString()),
                codec, properties, json, writer);
    }

    @Test
    @DisplayName("线性 START→AGENT→END：落库后 run SUCCESS，节点链状态正确")
    void linearAgentRunSucceeds() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{\"query\":\"你好\"}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        Map<String, String> statuses = statuses(run.getId());
        assertThat(statuses).containsEntry("start", "SUCCESS")
                .containsEntry("answer", "SUCCESS")
                .containsEntry("end", "SUCCESS");
        assertThat(agentCalls).containsExactly("101:你好");
        assertThat(store.run(run.getId()).getOutputJson()).isNotNull();
    }

    @Test
    @DisplayName("线性图数组把 END 排在 AGENT 前：引擎仍按执行序 START→AGENT→END 落状态与 trace")
    void linearTraceFollowsExecutionNotGraphOrder() {
        // 画布数组顺序 START, END, AGENT；边仍表达 START→AGENT→END
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.input.query\"}}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{\"query\":\"你好\"}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        // 调度与埋点都不认数组顺序：END 入边未决时不会被提前执行
        assertThat(traceWriter.openedNodes()).containsExactly("start", "answer", "end");
        assertThat(traceWriter.closedNodes())
                .containsExactly("start:SUCCESS", "answer:SUCCESS", "end:SUCCESS");
    }

    @Test
    @DisplayName("CONDITION 为真走 COND 边，另一分支 SKIPPED")
    void conditionTrueBranch() {
        String graph = conditionGraph();
        WorkflowRun run = start(graph, "{\"flag\":true}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        Map<String, String> statuses = statuses(run.getId());
        assertThat(statuses).containsEntry("yes", "SUCCESS").containsEntry("no", "SKIPPED");
        assertThat(agentCalls).hasSize(1);
        assertThat(agentCalls.get(0)).startsWith("1:");
    }

    @Test
    @DisplayName("CONDITION 为假走 DEFAULT 边")
    void conditionFalseBranch() {
        WorkflowRun run = start(conditionGraph(), "{\"flag\":false}");

        Map<String, String> statuses = statuses(run.getId());
        assertThat(statuses).containsEntry("yes", "SKIPPED").containsEntry("no", "SUCCESS");
        assertThat(agentCalls).hasSize(1);
        assertThat(agentCalls.get(0)).startsWith("2:");
    }

    @Test
    @DisplayName("节点失败无 ERROR 边：整 run FAILED，错误摘要进库")
    void failureWithoutErrorEdgeFailsRun() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT", "{\"agentId\":999}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));
        failingAgents.add(999L);

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(run.getId()).getErrorSummary()).contains("answer");
        assertThat(statuses(run.getId())).containsEntry("answer", "FAILED");
    }

    @Test
    @DisplayName("节点失败且画了 ERROR 边：错误边接管，run 仍可 SUCCESS")
    void failureWithErrorEdgeContinues() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT", "{\"agentId\":999}") + ","
                        + WorkflowTestGraphs.node("n3", "okEnd", "END", "{}") + ","
                        + WorkflowTestGraphs.node("n4", "errEnd", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n2", "n4", "ERROR", null));
        failingAgents.add(999L);

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        Map<String, String> statuses = statuses(run.getId());
        assertThat(statuses).containsEntry("answer", "FAILED")
                .containsEntry("okEnd", "SKIPPED")
                .containsEntry("errEnd", "SUCCESS");
    }

    @Test
    @DisplayName("HUMAN 节点：run 停 PAUSED_HUMAN，节点 WAITING_HUMAN，并写 OPEN 待办")
    void humanPausesRun() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "review", "HUMAN",
                        "{\"timeoutPolicy\":\"AUTO_REJECT\",\"timeoutMs\":60000}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.PAUSED_HUMAN.name());
        assertThat(statuses(run.getId())).containsEntry("review", "WAITING_HUMAN");
        assertThat(store.listOpenByRunId(run.getId())).hasSize(1);
        assertThat(store.listOpenByRunId(run.getId()).get(0).getNodeCode()).isEqualTo("review");
    }

    @Test
    @DisplayName("并行硬顶：FORK 分支数超过上限，FORK 节点 FAILED 且 run 收 FAILED（中文可读）")
    void forkExceedsParallelCap() {
        properties.setMaxParallelBranches(1);
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "fork", "PARALLEL_FORK", "{\"forkGroupId\":\"g1\"}") + ","
                        + WorkflowTestGraphs.node("n3", "a", "AGENT", "{\"agentId\":11}") + ","
                        + WorkflowTestGraphs.node("n4", "b", "AGENT", "{\"agentId\":12}") + ","
                        + WorkflowTestGraphs.node("n5", "join", "PARALLEL_JOIN", "{\"forkGroupId\":\"g1\"}") + ","
                        + WorkflowTestGraphs.node("n6", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n2", "n4", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n5", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n5", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e6", "n5", "n6", "NORMAL", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(run.getId()).getErrorSummary())
                .contains("并行分叉「fork」")
                .contains("超过上限 1")
                .contains("max-parallel-branches");
        assertThat(statuses(run.getId())).containsEntry("fork", "FAILED");
        assertThat(agentCalls).isEmpty();
    }

    @Test
    @DisplayName("整段超时：超过上限后 run FAILED，对话文案只说已停止，不带毫秒 / 配置键 / 撞限")
    void wallClockExceededFailsRun() {
        properties.setWallMs(1_000L);
        agentSleepMs = 1_100L;
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT", "{\"agentId\":101}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(run.getId()).getErrorSummary())
                .isEqualTo("工作流运行超时，已停止")
                .doesNotContain("撞限")
                .doesNotContain("wall-ms")
                .doesNotContain("ms");
    }

    @Test
    @DisplayName("运行时限：整段 / 单节点上限按分钟落库，截止时刻同时写入")
    void limitsResolveAndPersist() {
        properties.setWallMs(600_000L);
        String graph = WorkflowTestGraphs.graphWithLimits(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT", "{\"agentId\":101}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null),
                3, 1);

        WorkflowRun run = start(graph, "{}");
        WorkflowRun saved = store.run(run.getId());

        assertThat(saved.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        // 3 分钟整段 / 1 分钟单节点：落库是毫秒，界面才折成分钟展示
        assertThat(saved.getRunLimitMs()).isEqualTo(180_000L);
        assertThat(saved.getNodeLimitMs()).isEqualTo(60_000L);
        assertThat(saved.getRunDeadline()).isNotNull();
        assertThat(saved.getWaitingSince()).isNull();
    }

    @Test
    @DisplayName("未配置时限：整段跟平台硬顶走，单节点不设限")
    void limitsFallBackToPlatform() {
        properties.setWallMs(600_000L);
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{}");
        WorkflowRun saved = store.run(run.getId());

        assertThat(saved.getRunLimitMs()).isEqualTo(600_000L);
        // 0 = 该工作流未配置单节点上限（NULL 表示还没解析过）
        assertThat(saved.getNodeLimitMs()).isZero();
    }

    @Test
    @DisplayName("等待恢复：等待时长补回截止，waiting_since 清空（等待不计入运行时限）")
    void waitingTimeExtendsDeadline() {
        properties.setWallMs(600_000L);
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n3", "NORMAL", null));
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(1L);
        run.setWorkflowId(10L);
        run.setReleaseId(20L);
        run.setReleaseVersion(1);
        run.setStatus(WorkflowRunStatus.RUNNING.name());
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(7L);
        WorkflowTestGraphs.run(run, graph, "{}");
        // 模拟「十秒前就进入等待」：截止只到 30 秒后，等待这 10 秒必须补回来
        long now = System.currentTimeMillis();
        run.setRunDeadline(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(now + 30_000L), java.time.ZoneId.systemDefault()));
        run.setWaitingSince(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(now - 10_000L), java.time.ZoneId.systemDefault()));
        Long id = store.insertRun(run);

        engine.execute(run, 7L);

        WorkflowRun saved = store.run(id);
        assertThat(saved.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        long deadlineMs = saved.getRunDeadline().atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        // 恢复后截止 = 原截止 + 等待时长（>= 40 秒，容差 2 秒）
        assertThat(deadlineMs - now).isGreaterThanOrEqualTo(39_000L);
        assertThat(saved.getWaitingSince()).isNull();
    }

    @Test
    @DisplayName("单节点超时：节点与 run 都 FAILED，文案只说这一步已停止")
    void nodeLimitExceededFailsRun() {
        properties.setWallMs(600_000L);
        agentSleepMs = 1_100L;
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT", "{\"agentId\":101}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(1L);
        run.setWorkflowId(10L);
        run.setReleaseId(20L);
        run.setReleaseVersion(1);
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(7L);
        WorkflowTestGraphs.run(run, graph, "{}");
        // 直接用毫秒级上限：分钟精度在本用例里会把等待拉到一个数量级以上
        run.setNodeLimitMs(1_000L);
        Long id = store.insertRun(run);

        engine.execute(run, 7L);

        WorkflowRun saved = store.run(id);
        assertThat(saved.getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(saved.getErrorSummary())
                .isEqualTo("这一步运行超时，已停止")
                .doesNotContain("撞限");
        assertThat(statuses(id)).containsEntry("answer", "FAILED");
        // 超时节点不落成功：END 不会被点亮
        assertThat(statuses(id)).containsEntry("end", "PENDING");
    }

    @Test
    @DisplayName("埋点：开跑一次根 trace，每执行一个节点一开一收，终态收口 PASS")
    void writesTraceForEveryExecutedNode() {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT",
                        "{\"agentId\":101,\"inputMapping\":{\"message\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{\"query\":\"你好\"}");

        assertThat(traceWriter.events())
                .containsExactly("begin:" + run.getId(), "finish:SUCCESS");
        assertThat(traceWriter.openedNodes()).containsExactly("start", "answer", "end");
        assertThat(traceWriter.closedNodes())
                .containsExactly("start:SUCCESS", "answer:SUCCESS", "end:SUCCESS");
    }

    @Test
    @DisplayName("埋点降级：写入实现全程抛异常，工作流仍跑完并 SUCCESS")
    void traceFailureDoesNotBreakRun() {
        engine = buildEngine(new RecordingTraceWriter(true));
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "answer", "AGENT", "{\"agentId\":101}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));

        WorkflowRun run = start(graph, "{}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(agentCalls).hasSize(1);
    }

    private String conditionGraph() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "cond", "CONDITION", "{}") + ","
                        + WorkflowTestGraphs.node("n3", "yes", "AGENT", "{\"agentId\":1}") + ","
                        + WorkflowTestGraphs.node("n4", "no", "AGENT", "{\"agentId\":2}") + ","
                        + WorkflowTestGraphs.node("n5", "endYes", "END", "{}") + ","
                        + WorkflowTestGraphs.node("n6", "endNo", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "COND",
                        "{\"condition\":{\"==\":[{\"var\":\"input.flag\"},true]}}") + ","
                        + WorkflowTestGraphs.edge("e3", "n2", "n4", "DEFAULT", null) + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n5", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n6", "NORMAL", null));
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

    private Map<String, String> statuses(Long runId) {
        Map<String, String> map = new HashMap<>();
        for (WorkflowNodeRun row : store.listByRunId(runId)) {
            map.put(row.getNodeCode(), row.getStatus());
        }
        return map;
    }
}

