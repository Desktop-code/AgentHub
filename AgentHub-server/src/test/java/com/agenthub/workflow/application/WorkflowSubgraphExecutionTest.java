package com.agenthub.workflow.application;

import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.WorkflowCardProjector;
import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowIssue;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.RecordingWorkbenchTimeline;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流子图执行与投影的单测。所属域：workflow 的 application。
 *
 * <p>钉住这些口径（内存仓储 + 假 AGENT 端口 + mock 发布版本解析，不出站、不连库）：
 * <ul>
 *   <li>只跑 SUBGRAPH 节点钉死的 releaseId，入参走 inputMapping，出参挂回
 *       {@code $.outputs.<子图节点code>.*}；</li>
 *   <li>版本缺失 / 不可用按节点失败收口，文案面向用户；</li>
 *   <li>子图仍写在同一条 run / 同一张卡：子图节点行带范围前缀，scope 只补展示；</li>
 *   <li>子图内部失败时父 SUBGRAPH 节点失败，且能说清是哪个内部节点失败；</li>
 *   <li>子图内部等待回复后，回复仍回到那一行续跑，再回到父图。</li>
 * </ul>
 */
class WorkflowSubgraphExecutionTest {

    private static final long TENANT = 1L;
    private static final long ROOT_WORKFLOW = 20L;
    private static final long CHILD_WORKFLOW = 10L;
    private static final long CHILD_RELEASE = 100L;
    private static final long CHILD_AGENT = 501L;
    private static final long AFTER_AGENT = 502L;
    private static final long ASK_AGENT = 503L;
    private static final long CHILD_AFTER_AGENT = 504L;
    /** 并行分叉用例里的上游准备 Agent：两条出边从这里同时出发 */
    private static final long PREPARE_AGENT = 601L;
    /** 并行分叉用例里的循环体 Agent */
    private static final long LOOP_BODY_AGENT = 602L;

    private InMemoryWorkflowStore store;
    private ObjectMapper json;
    private WorkflowGraphCodec codec;
    private WorkflowSubgraphResolver resolver;
    private WorkflowScopeTreeBuilder scopeTree;
    private WorkflowEngine engine;
    private WorkflowRunAppService service;
    private final List<String> agentCalls = new ArrayList<>();
    private final List<Long> failingAgents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        json = new ObjectMapper();
        codec = new WorkflowGraphCodec(json);
        resolver = mock(WorkflowSubgraphResolver.class);
        // 宽松解析与严格解析同口径：mock 不会自动让 tryResolve 走 resolve，这里显式委托，
        // 否则只读投影（详情 / 回放）永远展开不出子图范围
        when(resolver.tryResolve(anyLong(), anyLong(), anyLong())).thenAnswer(invocation -> {
            try {
                return Optional.ofNullable(resolver.resolve(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
        });
        WorkflowProperties properties = new WorkflowProperties();
        properties.setTrialWallMs(5_000L);

        WorkflowAgentInvoker invoker = new WorkflowAgentInvoker() {
            @Override
            public String invoke(Long agentId, String message, long userId, long runId) {
                agentCalls.add(agentId + ":" + message);
                if (failingAgents.contains(agentId)) {
                    throw new WorkflowNodeFailure("mock：Agent 调用失败");
                }
                return replyOf(agentId, message);
            }

            @Override
            public Reply invokeForReply(Long agentId, String message, long userId, long runId) {
                agentCalls.add(agentId + ":" + message);
                if (failingAgents.contains(agentId)) {
                    throw new WorkflowNodeFailure("mock：Agent 调用失败");
                }
                return new Reply(replyOf(agentId, message), "");
            }
        };
        engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(), invoker,
                (toolId, args) -> "工具结果", codec, properties, json, new RecordingTraceWriter(),
                resolver);

        WorkflowRunAssembler assembler = new WorkflowRunAssembler(store.nodeRuns(), store.humanTasks());
        WorkflowHumanTaskAppService human = new WorkflowHumanTaskAppService(store.humanTasks(),
                store.nodeRuns(), store.runs(), engine, codec, assembler, () -> TENANT,
                new OperatorStub(), (module, action, targetType, targetId, summary) -> {
                }, store.defs(), json, new RecordingTraceWriter(), new RecordingWorkbenchTimeline());
        WorkflowTrialDispatcher dispatcher = new WorkflowTrialDispatcher(engine, store.runs(), Runnable::run);
        service = new WorkflowRunAppService(store.runs(), store.nodeRuns(), store.humanTasks(),
                new EmptyReleaseRepository(), store.defs(), () -> TENANT, new OperatorStub(), codec,
                engine, assembler, human, (module, action, targetType, targetId, summary) -> {
                }, properties, json, new RecordingTraceWriter(), dispatcher,
                new EmptyDraftRepository(), null);
        scopeTree = new WorkflowScopeTreeBuilder(resolver);
        service.setScopeTreeBuilder(scopeTree);
    }

    @Test
    @DisplayName("子图只跑钉死发布版：入参进子图、图级产出回到父图 outputs，下游能取到")
    void runsPinnedReleaseAndReturnsGraphOutput() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3, childAnswerGraph()));
        RecordingListener listener = new RecordingListener();

        WorkflowRun run = start(parentCallingChildGraph(), "{\"query\":\"你好\"}", listener);

        assertThat(statuses(run.getId())).containsEntry("call", "SUCCESS")
                .containsEntry("after", "SUCCESS").containsEntry("end", "SUCCESS");
        assertThat(agentCalls).containsExactly("501:你好", "502:答：你好");
        // 只按钉死的 releaseId 解析，不回落草稿 / 最新默认版
        verify(resolver).resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE);
        // 子图节点行仍在同一条父 run 下，编码带范围前缀；父图下游按原路径取到子图 END 产出
        List<WorkflowNodeRun> rows = store.listByRunId(run.getId());
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getNodeId()).startsWith("x");
            assertThat(row.getNodeCode()).endsWith("_answer");
            assertThat(row.getStatus()).isEqualTo("SUCCESS");
        });
        assertThat(listener.scopes).containsExactly("客服 · v3");
        assertThat(listener.scopeRows).isNotEmpty();
        // 直播事件里父 SUBGRAPH 节点始终就是子图框标题，不会先闪一次原始节点名
        assertThat(listener.nodeNames.get("n2")).isEqualTo("客服 · v3");

        // 回放：按库里同一批行走 persisted 投影，父 SUBGRAPH 用被调工作流名 + 版本，
        // 子图内部节点归到父节点编码下，展示编码还原成用户画布上的原名
        WorkflowScopeTree tree = scopeTree.build(store.run(run.getId()),
                codec.parse(store.run(run.getId()).getGraphJson()), rows);
        WorkflowRunView view = new WorkflowCardProjector("父图", "parent", null, json)
                .persisted(store.run(run.getId()), tree.rootNodes(), tree.rootOutgoing(),
                        rows, tree.childViews(), "");
        assertThat(view.nodes()).anySatisfy(node -> {
            assertThat(node.code()).isEqualTo("call");
            assertThat(node.name()).isEqualTo("客服 · v3");
        });
        assertThat(view.nodes()).anySatisfy(node -> {
            assertThat(node.code()).startsWith("x");
            assertThat(node.displayCode()).isEqualTo("answer");
            assertThat(node.group()).isEqualTo("call");
            assertThat(node.type()).isEqualTo("AGENT");
        });
    }

    @Test
    @DisplayName("直播投影：子图内部节点归到同一张卡的父 SUBGRAPH 框，展示编码不带范围前缀")
    void liveProjectionGroupsChildNodesUnderParentFrame() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3, childAnswerGraph()));
        WorkflowCardProjector projector = new WorkflowCardProjector("父图", "parent", null, json);

        WorkflowRun run = start(parentCallingChildGraph(), "{\"query\":\"你好\"}", projector);

        WorkflowRunView live = projector.finish(store.run(run.getId()), "");
        assertThat(live.nodes()).anySatisfy(node -> {
            assertThat(node.code()).isEqualTo("call");
            assertThat(node.name()).isEqualTo("客服 · v3");
        });
        assertThat(live.nodes()).anySatisfy(node -> {
            assertThat(node.code()).startsWith("x");
            assertThat(node.displayCode()).isEqualTo("answer");
            assertThat(node.group()).isEqualTo("call");
        });
    }

    @Test
    @DisplayName("上游成功后并行分叉：一边循环、一边子图，子图必须真正执行，不空等也不已跳过")
    void parallelForkWithLoopAndChildRunsChild() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3, childAnswerGraph()));

        WorkflowRun run = start(parallelLoopAndChildGraph(), "{\"query\":\"你好\",\"go\":true}", null);

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        // 循环与子图是同一上游节点选中的两条出边：两边都要跑，不能把子图当未选中分支跳过
        assertThat(statuses(run.getId()))
                .containsEntry("loop", "SUCCESS")
                .containsEntry("sub", "SUCCESS")
                .containsEntry("end", "SUCCESS");
        // 子图真的展开执行了：子图里的 AGENT 收到父图上准备的产出
        assertThat(agentCalls).contains("501:答：你好");
        // 循环体按配置跑满两轮，说明循环与子图各自推进、互不吞掉
        assertThat(agentCalls.stream().filter(call -> call.startsWith("602:")).toList())
                .containsExactly("602:1", "602:2");
    }

    @Test
    @DisplayName("子图只挂在条件节点未选中的出边上：按现有汇合语义跳过，不强行执行")
    void childOnUnselectedConditionEdgeIsSkipped() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3, childAnswerGraph()));

        WorkflowRun run = start(childOnUnselectedConditionEdgeGraph(),
                "{\"query\":\"你好\",\"go\":true}", null);

        // 条件节点只会选一条 COND / DEFAULT 边；挂在它额外 NORMAL 出边上的子图永远等不到选中，
        // 前驱终态后按「入边都没选中」收成 SKIPPED，这是既有条件分支语义，不为 SUBGRAPH 另开口子
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(statuses(run.getId()))
                .containsEntry("cond", "SUCCESS")
                .containsEntry("loop", "SUCCESS")
                .containsEntry("sub", "SKIPPED");
        assertThat(agentCalls).noneMatch(call -> call.startsWith("501:"));
    }

    @Test
    @DisplayName("发布版本不可用：SUBGRAPH 节点失败并给中文，不静默改跑草稿 / 最新版")
    void releaseUnavailableFailsNode() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenThrow(new WorkflowNodeFailure("子图绑定的发布版本已不存在，请重新选择版本"));

        WorkflowRun run = start(parentCallingChildGraph(), "{\"query\":\"你好\"}", null);

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(statuses(run.getId())).containsEntry("call", "FAILED");
        assertThat(store.run(run.getId()).getErrorSummary())
                .contains("子图绑定的发布版本已不存在")
                .doesNotContain("Exception").doesNotContain("http").doesNotContain(":");
    }

    @Test
    @DisplayName("子图内部失败：父 SUBGRAPH 失败，错误文案能说清内部哪个节点失败")
    void childFailureNamesInnerNode() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3, childAnswerGraph()));
        failingAgents.add(CHILD_AGENT);
        RecordingListener listener = new RecordingListener();

        WorkflowRun run = start(parentCallingChildGraph(), "{\"query\":\"你好\"}", listener);

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(run.getId()).getErrorSummary())
                .contains("客服 · v3").contains("answer").contains("失败");
        assertThat(statuses(run.getId())).containsEntry("call", "FAILED");
        assertThat(listener.changed)
                .anySatisfy(row -> {
                    assertThat(row.getNodeCode()).endsWith("_answer");
                    assertThat(row.getStatus()).isEqualTo("FAILED");
                });
        // 父节点收口时仍用「被调工作流名 · vN」，框标题不因失败退回原始节点名
        assertThat(listener.nodeNames.get("n2")).isEqualTo("客服 · v3");
    }

    @Test
    @DisplayName("运行时限的环：子图又调回调用链上的工作流，直接失败并给中文")
    void runtimeCycleFails() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3,
                        cycleBackGraph()));

        WorkflowRun run = start(parentCallingChildGraph(), "{\"query\":\"你好\"}", null);

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(run.getId()).getErrorSummary()).contains("循环调用");
    }

    @Test
    @DisplayName("子图里等待回复：同一张卡 / 同一条 run 挂起，回复后回到子图节点再回父图")
    void replyInsideChildResumesSameRun() {
        when(resolver.resolve(TENANT, CHILD_WORKFLOW, CHILD_RELEASE))
                .thenReturn(snapshot(CHILD_RELEASE, CHILD_WORKFLOW, "客服", "svc", 3,
                        childAskGraph()));
        WorkflowRun run = start(parentCallingChildGraph(), "{\"query\":\"你好\"}", null);

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.WAITING_REPLY.name());
        assertThat(statuses(run.getId())).containsEntry("call", "RUNNING");
        WorkflowNodeRun waiting = store.listByRunId(run.getId()).stream()
                .filter(row -> WorkflowNodeRunStatus.parse(row.getStatus()) == WorkflowNodeRunStatus.WAITING_REPLY)
                .findFirst().orElseThrow();
        assertThat(waiting.getNodeCode()).endsWith("_ask");
        // 恢复坐标：等待行属于子图范围，prepareReply 能按这层选出边，而不是在根图里找不到
        WorkflowScopeTree tree = scopeTree.build(store.run(run.getId()),
                codec.parse(store.run(run.getId()).getGraphJson()), store.listByRunId(run.getId()));
        assertThat(tree.scopeOfNode(waiting.getNodeId())).isNotNull();
        // 子图后续节点还没跑（START 已完成不算）
        assertThat(store.listByRunId(run.getId()))
                .filteredOn(row -> row.getNodeCode() != null
                        && (row.getNodeCode().endsWith("_cnext") || row.getNodeCode().endsWith("_cend")))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo("PENDING"));

        WorkflowRunDetail resumed = service.resumeWithReply(run.getId(), "Java", 7L, null);

        assertThat(resumed.getRun().getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(statuses(run.getId())).containsEntry("call", "SUCCESS");
        assertThat(agentCalls).contains("504:Java");
        WorkflowNodeRun ask = store.listByRunId(run.getId()).stream()
                .filter(row -> row.getNodeCode().endsWith("_ask")).findFirst().orElseThrow();
        assertThat(ask.getStatus()).isEqualTo("SUCCESS");
        assertThat(ask.getReplyText()).isEqualTo("Java");
    }

    @Test
    @DisplayName("静态检查：调用链成环与超过 3 层都在保存 / 发布前拦下")
    void guardBlocksCycleAndDepth() {
        WorkflowSubgraphGuard guard = new WorkflowSubgraphGuard(resolver);
        when(resolver.resolve(TENANT, 1L, 101L))
                .thenReturn(snapshot(101L, 1L, "一", "one", 1, cycleBackToRootGraph()));
        when(resolver.resolve(TENANT, 2L, 102L))
                .thenReturn(snapshot(102L, 2L, "二", "two", 1, callGraph(3L, 103L)));
        when(resolver.resolve(TENANT, 3L, 103L))
                .thenReturn(snapshot(103L, 3L, "三", "three", 1, callGraph(4L, 104L)));
        when(resolver.resolve(TENANT, 4L, 104L))
                .thenReturn(snapshot(104L, 4L, "四", "four", 1, callGraph(5L, 105L)));
        when(resolver.resolve(TENANT, 5L, 105L))
                .thenReturn(snapshot(105L, 5L, "五", "five", 1, emptyEndGraph()));

        List<WorkflowIssue> cycle = guard.check(TENANT, callGraph(1L, 101L));
        assertThat(cycle).anySatisfy(issue -> {
            assertThat(issue.getCode()).isEqualTo("SUBGRAPH_CYCLE");
            assertThat(issue.getMessage()).contains("循环");
        });

        List<WorkflowIssue> tooDeep = guard.check(TENANT, callGraph(2L, 102L));
        assertThat(tooDeep).anySatisfy(issue -> {
            assertThat(issue.getCode()).isEqualTo("SUBGRAPH_DEPTH");
            assertThat(issue.getMessage()).contains("超过 3 层");
        });
    }

    /** 父图：START → SUBGRAPH(call) → AGENT(after) → END；下游从 $.outputs.call.cend.text 取值 */
    private String parentCallingChildGraph() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "call", "SUBGRAPH",
                        "{\"subWorkflowId\":" + CHILD_WORKFLOW + ",\"subReleaseId\":" + CHILD_RELEASE
                                + ",\"inputMapping\":{\"query\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("n3", "after", "AGENT",
                        "{\"agentId\":" + AFTER_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.outputs.call.cend.text\"}}") + ","
                        + WorkflowTestGraphs.node("n4", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "NORMAL", null));
    }

    /** 子图：START → AGENT(answer) → END */
    private WorkflowGraph childAnswerGraph() {
        return graph(
                WorkflowTestGraphs.node("c1", "cstart", "START", "{}") + ","
                        + WorkflowTestGraphs.node("c2", "answer", "AGENT",
                        "{\"agentId\":" + CHILD_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("c3", "cend", "END",
                        "{\"outputMapping\":{\"text\":\"$.outputs.answer.text\"}}"),
                WorkflowTestGraphs.edge("ce1", "c1", "c2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("ce2", "c2", "c3", "NORMAL", null));
    }

    /**
     * 并行分叉父图：START → prepare(AGENT) 同时分出两条 NORMAL 边，
     * 一条进循环（loop → body，跑两轮后 EXIT），一条进子图 sub，最后都汇到 END。
     *
     * <p>子图节点刻意排在 END 之后，模拟现网图里「子图在右侧、循环先跑」的画布顺序：
     * 调度只认边，不应按节点 / 过程区顺序等待循环。
     */
    private String parallelLoopAndChildGraph() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{\"inputSchema\":{\"type\":\"object\"}}") + ","
                        + WorkflowTestGraphs.node("n2", "prepare", "AGENT",
                        "{\"agentId\":" + PREPARE_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("n3", "cond", "CONDITION",
                        "{\"expression\":{\"==\":[{\"var\":\"input.go\"},true]}}") + ","
                        + WorkflowTestGraphs.node("n4", "loop", "LOOP",
                        "{\"maxIterations\":2,\"onExceeded\":\"DOWNSTREAM\"}") + ","
                        + WorkflowTestGraphs.node("n5", "body", "AGENT",
                        "{\"agentId\":" + LOOP_BODY_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("n6", "end", "END", "{}") + ","
                        + WorkflowTestGraphs.node("n7", "sub", "SUBGRAPH",
                        "{\"subWorkflowId\":" + CHILD_WORKFLOW + ",\"subReleaseId\":" + CHILD_RELEASE
                                + ",\"inputMapping\":{\"query\":\"$.outputs.prepare.text\"}}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        // prepare 成功后同时选中 e2 / e5：循环与子图是并列的两条分支
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e5", "n2", "n7", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "COND",
                        "{\"condition\":{\"==\":[{\"var\":\"input.go\"},true]}}") + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n6", "DEFAULT", null) + ","
                        + WorkflowTestGraphs.edge("e6", "n4", "n5", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e7", "n5", "n4", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e8", "n4", "n6", "LOOP_EXIT", null) + ","
                        + WorkflowTestGraphs.edge("e9", "n7", "n6", "NORMAL", null));
    }

    /**
     * 复刻现网问题图的形状：条件节点 cond 有 COND（进循环）、DEFAULT（进结束）、
     * 以及一条 NORMAL（进子图）。条件节点只会选一条 COND / DEFAULT 边，
     * NORMAL 出边永远不会被选中，子图应按既有汇合语义跳过。
     */
    private String childOnUnselectedConditionEdgeGraph() {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{\"inputSchema\":{\"type\":\"object\"}}") + ","
                        + WorkflowTestGraphs.node("n2", "prepare", "AGENT",
                        "{\"agentId\":" + PREPARE_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("n3", "cond", "CONDITION",
                        "{\"expression\":{\"==\":[{\"var\":\"input.go\"},true]}}") + ","
                        + WorkflowTestGraphs.node("n4", "loop", "LOOP",
                        "{\"maxIterations\":2,\"onExceeded\":\"DOWNSTREAM\"}") + ","
                        + WorkflowTestGraphs.node("n5", "body", "AGENT",
                        "{\"agentId\":" + LOOP_BODY_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.vars.loop.iteration\"}}") + ","
                        + WorkflowTestGraphs.node("n6", "end", "END", "{}") + ","
                        + WorkflowTestGraphs.node("n7", "sub", "SUBGRAPH",
                        "{\"subWorkflowId\":" + CHILD_WORKFLOW + ",\"subReleaseId\":" + CHILD_RELEASE
                                + ",\"inputMapping\":{\"query\":\"$.input.query\"}}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "COND",
                        "{\"condition\":{\"==\":[{\"var\":\"input.go\"},true]}}") + ","
                        + WorkflowTestGraphs.edge("e4", "n3", "n6", "DEFAULT", null) + ","
                        // 问题图里这条边就是 NORMAL：条件执行器只认 COND / DEFAULT，它不会被选中
                        + WorkflowTestGraphs.edge("e5", "n3", "n7", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e6", "n4", "n5", "LOOP_BODY", null) + ","
                        + WorkflowTestGraphs.edge("e7", "n5", "n4", "LOOP_BACK", null) + ","
                        + WorkflowTestGraphs.edge("e8", "n4", "n6", "LOOP_EXIT", null));
    }

    /** 子图：START → AGENT(ask，等回复) → AGENT(后续) → END */
    private WorkflowGraph childAskGraph() {
        return graph(
                WorkflowTestGraphs.node("c1", "cstart", "START", "{}") + ","
                        + WorkflowTestGraphs.node("c2", "ask", "AGENT",
                        "{\"agentId\":" + ASK_AGENT + ",\"awaitReply\":true,"
                                + "\"inputMapping\":{\"message\":\"$.input.query\"}}") + ","
                        + WorkflowTestGraphs.node("c3", "cnext", "AGENT",
                        "{\"agentId\":" + CHILD_AFTER_AGENT
                                + ",\"inputMapping\":{\"message\":\"$.input.reply\"}}") + ","
                        + WorkflowTestGraphs.node("c4", "cend", "END", "{}"),
                WorkflowTestGraphs.edge("ce1", "c1", "c2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("ce2", "c2", "c3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("ce3", "c3", "c4", "NORMAL", null));
    }

    /** 子图：START → SUBGRAPH(调回父工作流) → END，用于运行时环 */
    private WorkflowGraph cycleBackGraph() {
        return graph(
                WorkflowTestGraphs.node("c1", "cstart", "START", "{}") + ","
                        + WorkflowTestGraphs.node("c2", "back", "SUBGRAPH",
                        "{\"subWorkflowId\":" + ROOT_WORKFLOW + ",\"subReleaseId\":999}") + ","
                        + WorkflowTestGraphs.node("c3", "cend", "END", "{}"),
                WorkflowTestGraphs.edge("ce1", "c1", "c2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("ce2", "c2", "c3", "NORMAL", null));
    }

    /** 静态检查用：根工作流再次出现（环） */
    private WorkflowGraph cycleBackToRootGraph() {
        return callGraph(1L, 101L);
    }

    /** 一张只含 START → SUBGRAPH → END 的图 */
    private WorkflowGraph callGraph(long workflowId, long releaseId) {
        return graph(
                WorkflowTestGraphs.node("s1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("s2", "call" + workflowId, "SUBGRAPH",
                        "{\"subWorkflowId\":" + workflowId + ",\"subReleaseId\":" + releaseId + "}") + ","
                        + WorkflowTestGraphs.node("s3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("se1", "s1", "s2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("se2", "s2", "s3", "NORMAL", null));
    }

    /** 一张只含 START → END 的图，用于深度链末端 */
    private WorkflowGraph emptyEndGraph() {
        return graph(
                WorkflowTestGraphs.node("e1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("e2", "end", "END", "{}"),
                WorkflowTestGraphs.edge("ee1", "e1", "e2", "NORMAL", null));
    }

    private WorkflowGraph graph(String nodes, String edges) {
        return codec.parse(WorkflowTestGraphs.graph(nodes, edges));
    }

    private WorkflowSubgraphSnapshot snapshot(Long releaseId, Long workflowId, String name,
                                              String code, int version, WorkflowGraph graph) {
        return new WorkflowSubgraphSnapshot(releaseId, workflowId, name, code, version, graph);
    }

    private WorkflowRun start(String graphJson, String inputJson, WorkflowRunListener listener) {
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(TENANT);
        run.setWorkflowId(ROOT_WORKFLOW);
        run.setReleaseId(200L);
        run.setReleaseVersion(1);
        run.setStatus(WorkflowRunStatus.CREATED.name());
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(7L);
        WorkflowTestGraphs.run(run, graphJson, inputJson);
        Long id = store.insertRun(run);
        engine.execute(run, 7L, listener);
        return store.run(id);
    }

    private Map<String, String> statuses(Long runId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (WorkflowNodeRun row : store.listByRunId(runId)) {
            map.put(row.getNodeCode(), row.getStatus());
        }
        return map;
    }

    private String replyOf(Long agentId, String message) {
        if (agentId == CHILD_AGENT) {
            return "答：" + message;
        }
        if (agentId == AFTER_AGENT) {
            return "下游：" + message;
        }
        if (agentId == ASK_AGENT) {
            return "请选择实现语言";
        }
        if (agentId == CHILD_AFTER_AGENT) {
            return "子图后续：" + message;
        }
        return "答：" + message;
    }

    /** 观察者：收集子图框与每次节点变更，验证同一张卡上确实补了子图行 */
    private static final class RecordingListener implements WorkflowRunListener {
        private final List<String> scopes = new ArrayList<>();
        private final List<WorkflowNodeRun> scopeRows = new ArrayList<>();
        private final List<WorkflowNodeRun> changed = new ArrayList<>();
        /** 变更事件里父节点最后一次拿到的定义（用来验证失败收口仍用子图标题，而非原始节点名） */
        private final Map<String, String> nodeNames = new LinkedHashMap<>();

        @Override
        public void runStarted(List<WorkflowNode> nodes, List<WorkflowNodeRun> rows) {
            // 本类只关心子图补报与节点变更
        }

        @Override
        public void scopeStarted(com.agenthub.workflow.domain.model.WorkflowScopeView scope) {
            scopes.add(scope.label());
            scopeRows.addAll(scope.rows());
        }

        @Override
        public void nodeChanged(WorkflowNode node, WorkflowNodeRun row) {
            changed.add(row);
            nodeNames.put(row.getNodeId(), node.getName());
        }
    }

    /** 单测用操作人：固定用户 7，不读 Sa-Token */
    private static final class OperatorStub implements OperatorContext {
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

    /** 发布版本仓储空实现：本类用例直接喂快照，不经过发布查询 */
    private static final class EmptyReleaseRepository implements WorkflowReleaseRepository {
        @Override
        public int maxVersion(Long workflowId) {
            return 0;
        }

        @Override
        public Long insert(WorkflowRelease release) {
            return null;
        }

        @Override
        public Optional<WorkflowRelease> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<WorkflowRelease> listByWorkflowId(Long workflowId) {
            return List.of();
        }
    }

}
