package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「工作流在对话里停等并接着同一条运行继续」的单测。所属域：workflow 的 application。
 *
 * <p>钉住四件事（内存仓储 + 假 AGENT 端口，不出站、不连库）：
 * <ol>
 *   <li>AGENT 节点勾了 {@code awaitReply} 且还没有用户回复时，run 停 {@code WAITING_REPLY}，
 *       节点也停 {@link com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus#WAITING_REPLY}，
 *       问句留在节点产出里（卡片正文与回放都读它）；</li>
 *   <li>后续节点此时一个都不跑；即使用 END 收尾也不得把 run 收成 SUCCESS；</li>
 *   <li>续跑入口把回复写进 {@code input.reply}，同一条 run 从挂起处继续，条件边读到这句回复；</li>
 *   <li>等待中点停止按现网中断收口，不会再自己续跑。</li>
 * </ol>
 */
class WorkflowReplyWaitTest {

    private InMemoryWorkflowStore store;
    private WorkflowEngine engine;
    private WorkflowRunAppService service;
    private ObjectMapper json;
    private final List<String> agentCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        json = new ObjectMapper();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        WorkflowProperties properties = new WorkflowProperties();
        properties.setTrialWallMs(5_000L);
        engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                new WorkflowAgentInvoker() {
                    @Override
                    public String invoke(Long agentId, String message, long userId, long runId) {
                        return replyOf(agentId, message).text();
                    }

                    @Override
                    public Reply invokeForReply(Long agentId, String message, long userId, long runId) {
                        return replyOf(agentId, message);
                    }

                    /**
                     * 询问节点回一个带 2 个选项的问句（模拟 agenthub_choice 的产出）；
                     * 其它节点回一段可识别的正文。
                     */
                    private Reply replyOf(Long agentId, String message) {
                        agentCalls.add(agentId + ":" + message);
                        if (agentId == ASK_AGENT) {
                            return new Reply("请选择实现语言：Python / Java",
                                    "{\"question\":\"你想用哪种语言实现？\","
                                            + "\"options\":[{\"id\":\"python\",\"label\":\"Python\"},"
                                            + "{\"id\":\"java\",\"label\":\"Java\"}]}");
                        }
                        return new Reply("答：" + message, "");
                    }
                },
                (toolId, args) -> "工具结果",
                codec, properties, json, new RecordingTraceWriter());
        WorkflowRunAssembler assembler = new WorkflowRunAssembler(store.nodeRuns(), store.humanTasks());
        WorkflowHumanTaskAppService human = new WorkflowHumanTaskAppService(store.humanTasks(), store.nodeRuns(),
                store.runs(), engine, codec, assembler, () -> 1L, new OperatorStub(),
                (module, action, targetType, targetId, summary) -> {
                }, store.defs(), json, new RecordingTraceWriter(), new com.agenthub.workflow.support.RecordingWorkbenchTimeline());
        service = new WorkflowRunAppService(store.runs(), store.nodeRuns(), store.humanTasks(),
                new EmptyReleaseRepository(), store.defs(), () -> 1L, new OperatorStub(), codec, engine,
                assembler, human, (module, action, targetType, targetId, summary) -> {
                }, properties, json, new RecordingTraceWriter());
    }

    /** 单测用操作人：固定返回用户 7，不做任何 Sa-Token 调用。 */
    private static final class OperatorStub implements com.agenthub.common.web.OperatorContext {

        @Override
        public Long userIdOrNull() {
            return 7L;
        }

        @Override
        public long requireUserId() {
            return 7L;
        }

        @Override
        public String usernameOrNull() {
            return "tester";
        }

        @Override
        public String clientIp() {
            return "127.0.0.1";
        }

        @Override
        public String userAgent() {
            return "test";
        }

        @Override
        public String requestMethod() {
            return "POST";
        }

        @Override
        public String requestUri() {
            return "/test";
        }
    }

    /** 发布版本仓储空实现：本类用例直接喂图，不经过 release 解析。 */
    private static final class EmptyReleaseRepository
            implements com.agenthub.workflow.domain.repository.WorkflowReleaseRepository {

        @Override
        public int maxVersion(Long workflowId) {
            return 0;
        }

        @Override
        public Long insert(com.agenthub.workflow.domain.model.WorkflowRelease release) {
            return null;
        }

        @Override
        public java.util.Optional<com.agenthub.workflow.domain.model.WorkflowRelease> findById(Long id) {
            return java.util.Optional.empty();
        }

        @Override
        public List<com.agenthub.workflow.domain.model.WorkflowRelease> listByWorkflowId(Long workflowId) {
            return List.of();
        }
    }

    /**
     * 勾了等回复的 AGENT 节点：run 与节点都停在等待回复，问句进节点产出，后续节点不跑。
     */
    @Test
    @DisplayName("等用户回复：run/节点都挂起，问句留在产出，后续节点不跑")
    void agentNodeWaitsForReply() {
        WorkflowRun run = start(awaitGraph(), "{\"query\":\"做个2048\"}");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.WAITING_REPLY.name());
        Map<String, String> statuses = statuses(run.getId());
        assertThat(statuses).containsEntry("start", "SUCCESS")
                .containsEntry("ask", "WAITING_REPLY")
                .containsEntry("impl", "PENDING");
        // 问句落在节点产出里：卡片正文按节点产出增量拼接，回放也读这一份
        String output = nodeOutput(run.getId(), "ask");
        assertThat(output).contains("请选择实现语言");
        assertThat(readText(output, "question")).contains("请选择实现语言");
        // 实现节点一个都没跑：本轮只留下询问
        assertThat(agentCalls).hasSize(1);
        // 没有建人工待办：这条路不该要求用户去待办页点通过
        assertThat(store.listOpenByRunId(run.getId())).isEmpty();
    }

    /**
     * 续跑：回复写进 input.reply，同一条 run 继续；条件边按回复选路，不新建 run。
     */
    @Test
    @DisplayName("续跑：回复写入同一条 run，条件边读到 input.reply 后继续往下")
    void resumeWithReplyContinuesSameRun() {
        WorkflowRun run = start(awaitGraph(), "{\"query\":\"做个2048\"}");
        Long runId = run.getId();

        WorkflowRunDetail detail = service.resumeWithReply(runId, "Python", 7L, null);

        assertThat(detail.getRun().getId()).isEqualTo(runId);
        assertThat(detail.getRun().getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        Map<String, String> statuses = statuses(runId);
        assertThat(statuses).containsEntry("ask", "SUCCESS")
                .containsEntry("impl", "SUCCESS")
                .containsEntry("end", "SUCCESS");
        // 回复落进输入：实现节点读到的文本里带上了用户这句
        assertThat(store.run(runId).getInputJson()).contains("Python");
        assertThat(agentCalls).anyMatch(call -> call.startsWith(IMPL_AGENT + ":") && call.contains("Python"));
        // 只有一条 run：续跑不是新开
        assertThat(store.listRuns(WORKFLOW_ID, 10)).hasSize(1);
    }

    /** 续跑时 run 已不在等待：明确 400，不改任何状态。 */
    @Test
    @DisplayName("续跑入口：run 不在等待回复时拒绝，且不动状态")
    void resumeRejectsWhenNotWaiting() {
        WorkflowRun run = start(linearGraph(), "{}");
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());

        assertThatThrownBy(() -> service.resumeWithReply(run.getId(), "Python", 7L, null))
                .hasMessageContaining("不在等待回复");
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
    }

    /** 等待中点停止：run 收 CANCELLED，节点不再续跑。 */
    @Test
    @DisplayName("等待时停止：run 收 CANCELLED，刷新后不会自己续跑")
    void cancelWhileWaiting() {
        WorkflowRun run = start(awaitGraph(), "{\"query\":\"做个2048\"}");

        service.cancel(run.getId());

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.CANCELLED.name());
        Map<String, String> statuses = statuses(run.getId());
        assertThat(statuses).containsEntry("ask", "CANCELLED")
                .containsEntry("impl", "SKIPPED");
    }

    /** 同一会话里能按会话找到等待中的 run；取消后查不到。 */
    @Test
    @DisplayName("按会话查等待回复：等待时命中，取消后为空")
    void findWaitingReplyBySession() {
        WorkflowRun run = start(awaitGraph(), "{\"query\":\"做个2048\"}", 66L);

        assertThat(service.findWaitingReplyRun(66L)).isPresent();
        assertThat(service.findWaitingReplyRun(66L).orElseThrow().getId()).isEqualTo(run.getId());

        service.cancel(run.getId());
        assertThat(service.findWaitingReplyRun(66L)).isEmpty();
    }

    /**
     * 等待卡发布前 run 必须已经落 WAITING_REPLY。
     *
     * <p>用户看到选项就会立刻点；若这一拍库里 run 还是 RUNNING，会话入口按「等待中的运行」
     * 查不到它，回复会被当成新问题重新过一遍入口。这里在节点回调里读库，钉住发布顺序。
     */
    @Test
    @DisplayName("等待卡发布顺序：run 已落 WAITING_REPLY 后才推带选项的节点")
    void waitingCardIsPublishedOnlyAfterRunIsWaiting() {
        WorkflowRun run = createRun(awaitGraphWithChoice(), "{\"query\":\"做个2048\"}", 88L);
        AtomicReference<String> statusAtPublish = new AtomicReference<>();
        engine.execute(store.run(run.getId()), 7L, new WorkflowRunListener() {
            @Override
            public void runStarted(List<WorkflowNode> nodes, List<WorkflowNodeRun> rows) {
                // 开跑这一拍不关心：只验证等待节点回调时 run 的库内状态
            }

            @Override
            public void nodeChanged(WorkflowNode node, WorkflowNodeRun row) {
                if ("ask".equals(node.getCode()) && "WAITING_REPLY".equals(row.getStatus())) {
                    statusAtPublish.set(store.run(run.getId()).getStatus());
                }
            }
        });

        assertThat(statusAtPublish.get()).isEqualTo(WorkflowRunStatus.WAITING_REPLY.name());
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.WAITING_REPLY.name());
    }

    /** 询问节点带选项：选项落在节点产出的 choice 里，可被点选校验命中；不认识的 id 取不到文案。 */
    @Test
    @DisplayName("询问节点选项：落库为 choice，点选按 id 取回服务端文案")
    void waitingNodeKeepsChoiceAndResolvesLabel() {
        WorkflowRun run = start(awaitGraphWithChoice(), "{\"query\":\"做个2048\"}");
        String output = nodeOutput(run.getId(), "ask");

        JsonNode choice = readChoice(output);
        assertThat(choice).isNotNull();
        assertThat(choice.path("question").asText()).contains("哪种语言");
        assertThat(choice.path("options")).hasSize(2);

        assertThat(service.waitingOptionLabel(run.getId(), "python")).contains("Python");
        // 大小写不敏感（模型与前端可能大小写漂移）
        assertThat(service.waitingOptionLabel(run.getId(), "PYTHON")).contains("Python");
        // 不属于这一步的 id：不猜文案
        assertThat(service.waitingOptionLabel(run.getId(), "golang")).isEmpty();
    }

    /** 点选续跑：与输入框提交同一条写入（input.reply 就是选项文案）。 */
    @Test
    @DisplayName("点选续跑：选项文案写进 input.reply，同一条 run 继续")
    void optionReplyContinuesSameRun() {
        WorkflowRun run = start(awaitGraphWithChoice(), "{\"query\":\"做个2048\"}");
        Long runId = run.getId();

        WorkflowRunDetail detail = service.resumeWithReply(runId, "Python", 7L, null);

        assertThat(detail.getRun().getId()).isEqualTo(runId);
        assertThat(detail.getRun().getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(store.run(runId).getInputJson()).contains("Python");
        assertThat(agentCalls).anyMatch(call -> call.startsWith(IMPL_AGENT + ":") && call.contains("Python"));
        assertThat(store.listRuns(WORKFLOW_ID, 10)).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // 图与数据准备
    // ---------------------------------------------------------------------

    private static final long WORKFLOW_ID = 10L;
    private static final long ASK_AGENT = 201L;
    private static final long IMPL_AGENT = 202L;

    /**
     * 「问一句 → 条件边（已有回复就走实现）→ 实现 → END」的图。
     *
     * <p>条件读的是 {@code input.reply}：这正是续跑入口写进去的字段。
     */
    private static String awaitGraph() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "ask", "AGENT",
                        "{\"agentId\":201,\"awaitReply\":true}") + ","
                        + WorkflowTestGraphs.node("n3", "cond", "CONDITION", "{}") + ","
                        + WorkflowTestGraphs.node("n4", "impl", "AGENT",
                        "{\"agentId\":202,\"inputMapping\":{\"message\":\"$.input.reply\"}}") + ","
                        + WorkflowTestGraphs.node("n5", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "COND",
                        "{\"condition\":{\"!!\":[{\"var\":\"input.reply\"}]}}") + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n5", "DEFAULT", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n5", "NORMAL", null));
    }

    /**
     * 与 {@link #awaitGraph()} 同构，但询问节点带可点选项。
     *
     * <p>选项由 Agent 通过平台内置 {@code agenthub_choice} 产出（本窄测用端口替身直接给出 JSON），
     * 引擎只做透传并落进节点产出。
     */
    private static String awaitGraphWithChoice() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "ask", "AGENT",
                        "{\"agentId\":201,\"awaitReply\":true}") + ","
                        + WorkflowTestGraphs.node("n3", "cond", "CONDITION", "{}") + ","
                        + WorkflowTestGraphs.node("n4", "impl", "AGENT",
                        "{\"agentId\":202,\"inputMapping\":{\"message\":\"$.input.reply\"}}") + ","
                        + WorkflowTestGraphs.node("n5", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "COND",
                        "{\"condition\":{\"!!\":[{\"var\":\"input.reply\"}]}}") + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n5", "DEFAULT", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n4", "n5", "NORMAL", null));
    }

    /** 普通三节点线性图（不等回复），用于「不在等待」的对照。 */
    private static String linearGraph() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "impl", "AGENT", "{\"agentId\":202}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));
    }

    private WorkflowRun start(String graphJson, String inputJson) {
        return start(graphJson, inputJson, null);
    }

    private WorkflowRun start(String graphJson, String inputJson, Long workbenchSessionId) {
        WorkflowRun run = createRun(graphJson, inputJson, workbenchSessionId);
        engine.execute(store.run(run.getId()), 7L);
        return store.run(run.getId());
    }

    /**
     * 建 run 与 PENDING 节点行，但不执行：需要自己挂监听器观察发布顺序的用例用。
     */
    private WorkflowRun createRun(String graphJson, String inputJson, Long workbenchSessionId) {
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(1L);
        run.setWorkflowId(WORKFLOW_ID);
        run.setReleaseId(20L);
        run.setReleaseVersion(1);
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType("WORKBENCH");
        run.setTriggeredBy(7L);
        run.setWorkbenchSessionId(workbenchSessionId);
        WorkflowTestGraphs.run(run, graphJson, inputJson);
        Long id = store.insertRun(run);
        // 建 run 时预建的 PENDING 行：引擎会按缺失自愈，但这里显式预建以贴近现网
        List<WorkflowNodeRun> rows = new ArrayList<>();
        for (JsonNode node : readNodes(graphJson)) {
            WorkflowNodeRun row = new WorkflowNodeRun();
            row.setRunId(id);
            row.setNodeId(node.path("id").asText());
            row.setNodeCode(node.path("code").asText());
            row.setNodeType(node.path("type").asText());
            row.setAttempt(1);
            row.setStatus("PENDING");
            rows.add(row);
        }
        store.insertNodeRuns(rows);
        return store.run(id);
    }

    private static List<JsonNode> readNodes(String graphJson) {
        try {
            List<JsonNode> list = new ArrayList<>();
            JsonNode root = new ObjectMapper().readTree(graphJson);
            root.path("nodes").forEach(list::add);
            return list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, String> statuses(Long runId) {
        Map<String, String> map = new HashMap<>();
        for (WorkflowNodeRun row : store.listByRunId(runId)) {
            map.put(row.getNodeCode(), row.getStatus());
        }
        return map;
    }

    private String nodeOutput(Long runId, String code) {
        for (WorkflowNodeRun row : store.listByRunId(runId)) {
            if (code.equals(row.getNodeCode())) {
                return row.getOutputJson() == null ? "" : row.getOutputJson();
            }
        }
        return "";
    }

    private String readText(String outputJson, String field) {
        try {
            return json.readTree(outputJson).path(field).asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 读节点产出里的 choice；没有或解析失败返回 null。
     *
     * @param outputJson 节点产出
     * @return choice 节点
     */
    private JsonNode readChoice(String outputJson) {
        try {
            JsonNode node = json.readTree(outputJson).path("choice");
            return node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
