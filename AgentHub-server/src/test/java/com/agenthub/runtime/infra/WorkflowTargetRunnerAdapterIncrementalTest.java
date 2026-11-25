package com.agenthub.runtime.infra;

import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowWorkbenchOutcome;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流目标卡片「正文按节点增量出站」的源端用例。所属域：runtime 的 infra。
 *
 * <p>钉住三件不可退让的事（全部 mock、不连库、不起服务、不打模型）：
 * <ol>
 *   <li>运行中每完成一个 AGENT 节点就推一份 {@code RUNNING} 快照，正文是**已流出片段的拼接**，
 *       不是只推 {@code answer=""} 等收尾一次性倒出；</li>
 *   <li>收尾正文「只增不减」：成功时保留已流出的片段，绝不拿 END 映射那段整段替换
 *       （那正是页面上「文字清空 → 再整段倒出」的来源）；失败时把原因接在后面；</li>
 *   <li>返回的终答与卡片终态正文同一份：落库 / 回放与直播看到的是一段文字。</li>
 * </ol>
 */
class WorkflowTargetRunnerAdapterIncrementalTest {

    /** 外层墙钟：给足，避免时间因素干扰断言。 */
    private static final long DEADLINE = System.nanoTime() + 60_000_000_000L;

    private WorkflowRunAppService runs;
    private RecordingProgress progress;
    private WorkflowTargetRunnerAdapter adapter;

    @BeforeEach
    void setUp() {
        runs = mock(WorkflowRunAppService.class);
        progress = new RecordingProgress();
        adapter = new WorkflowTargetRunnerAdapter(runs, new ObjectMapper());
    }

    /** 两个 AGENT 节点先后完成：正文一拍一拍变长，收尾保留已流出文字。 */
    @Test
    void streamsAnswerPerFinishedAgentNodeAndKeepsItAtFinish() {
        WorkflowNode start = node("s", "start", "开始", "START");
        WorkflowNode first = node("a1", "policy_points", "知识问答", "AGENT");
        WorkflowNode second = node("a2", "minutes_guide", "纪要写法", "AGENT");
        WorkflowNode end = node("e", "end", "结束", "END");
        List<WorkflowNode> graph = List.of(start, first, second, end);
        List<WorkflowNodeRun> rows = List.of(pending(start), pending(first), pending(second), pending(end));

        // 收尾文本故意与已流出片段不同：证明终态不会拿它整段替换
        WorkflowRun run = run("SUCCESS", "{\"end\":{\"answer\":\"END 汇总文本\"}}", null);
        when(runs.runFromWorkbench(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(streams(graph, rows, run,
                        step(first, "SUCCESS", "{\"policy_points\":{\"answer\":\"第一段要点\"}}"),
                        step(second, "SUCCESS", "{\"minutes_guide\":{\"answer\":\"第二段写法\"}}")));

        WorkflowTargetRunner.WorkflowTargetResult result = adapter.run(
                intent(), spec(), "请企业综合台根据公司制度整理知识要点", 7L, 51L, DEADLINE, progress);

        // 开局先推一份「全未开始」的快照：卡片当拍就能画时间轴，不空等
        assertEquals("RUNNING", progress.views.get(0).status());
        assertEquals("", progress.views.get(0).answer());
        assertTrue(progress.views.size() >= 4, "节点完成与收尾都必须各推一份快照");

        // 第一个 AGENT 完成：这一拍就能看到它产出的那一段
        assertEquals("第一段要点", progress.views.get(1).answer());
        // 第二个 AGENT 完成：在上一段后面追加，不是替换
        assertEquals("第一段要点\n\n第二段写法", progress.views.get(2).answer());

        WorkflowRunView last = progress.last();
        assertEquals("SUCCESS", last.status());
        // 收尾「只增不减」：不拿 END 映射整段替换已流出文字
        assertEquals("第一段要点\n\n第二段写法", last.answer());
        assertFalse(last.answer().contains("END 汇总文本"));
        // 终答与卡片同一份：落库 / 回放与直播一致
        assertEquals("第一段要点\n\n第二段写法", result.turn().getAssistantText());
        assertEquals(last, result.view());
        // START / END 不是执行步骤，不进时间轴
        assertEquals(2, last.nodes().size());
    }

    /**
     * 节点状态多次迁移：第一次 SUCCESS 还没成形（抽不出正文）不得把节点永久封死。
     *
     * <p>这正是不出字的主因之一：引擎会先把节点标成 SUCCESS，output_json 随后才补齐。
     * 若「见过这个节点」就登记去重位，后面那次真产出就被吞掉，正文只能在收尾整段倒出。
     */
    @Test
    void nodeWithoutTextOnFirstSuccessStillStreamsWhenOutputArrives() {
        WorkflowNode start = node("s", "start", "开始", "START");
        WorkflowNode first = node("a1", "policy_points", "知识问答", "AGENT");
        WorkflowNode end = node("e", "end", "结束", "END");
        List<WorkflowNode> graph = List.of(start, first, end);
        List<WorkflowNodeRun> rows = List.of(pending(start), pending(first), pending(end));
        WorkflowRun run = run("SUCCESS", "{\"end\":{\"answer\":\"END 汇总文本\"}}", null);
        // 同一节点连续两次 SUCCESS：先空输出，再补上正文；第三拍重复同一份，不得重复追加
        when(runs.runFromWorkbench(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(streams(graph, rows, run,
                        step(first, "SUCCESS", "{}"),
                        step(first, "SUCCESS", "{\"policy_points\":{\"answer\":\"第一段要点\"}}"),
                        step(first, "SUCCESS", "{\"policy_points\":{\"answer\":\"第一段要点\"}}")));

        WorkflowTargetRunner.WorkflowTargetResult result = adapter.run(
                intent(), spec(), "整理知识要点", 7L, 54L, DEADLINE, progress);

        // 第一拍抽不出正文：快照仍是空正文（不得凭空造字）
        assertEquals("", progress.views.get(1).answer());
        // 第二拍补上输出：这一拍就该看见文字，而不是等收尾
        assertEquals("第一段要点", progress.views.get(2).answer());
        // 第三拍重复同一节点：不重复追加
        assertEquals("第一段要点", progress.views.get(3).answer());
        assertEquals("第一段要点", progress.last().answer());
        assertEquals("第一段要点", result.turn().getAssistantText());
    }

    /** 失败收口：原因接在已流出正文后面，不能把用户已经看到的文字抹掉。 */
    @Test
    void failureAppendsReasonInsteadOfWipingStreamedText() {
        WorkflowNode start = node("s", "start", "开始", "START");
        WorkflowNode first = node("a1", "policy_points", "知识问答", "AGENT");
        WorkflowNode end = node("e", "end", "结束", "END");
        List<WorkflowNode> graph = List.of(start, first, end);
        List<WorkflowNodeRun> rows = List.of(pending(start), pending(first), pending(end));
        WorkflowRun run = run("FAILED", "{\"policy_points\":{\"answer\":\"第一段要点\"}}",
                "节点 policy_points 执行失败");
        when(runs.runFromWorkbench(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(streams(graph, rows, run,
                        step(first, "SUCCESS", "{\"policy_points\":{\"answer\":\"第一段要点\"}}")));

        WorkflowTargetRunner.WorkflowTargetResult result = adapter.run(
                intent(), spec(), "整理知识要点", 7L, 52L, DEADLINE, progress);

        String answer = progress.last().answer();
        assertTrue(answer.startsWith("第一段要点"), "已流出的正文必须留在原位");
        assertTrue(answer.contains("运行失败"), "失败原因要接在后面");
        assertEquals(answer, result.turn().getAssistantText());
        assertEquals("FAILED", progress.last().status());
    }

    /** 没有任何 AGENT 正文（纯工具图）：收尾用 run 的终答，不留下空卡片。 */
    @Test
    void emptyStreamFallsBackToRunAnswer() {
        WorkflowNode start = node("s", "start", "开始", "START");
        WorkflowNode tool = node("t1", "fetch", "取数", "TOOL");
        WorkflowNode end = node("e", "end", "结束", "END");
        List<WorkflowNode> graph = List.of(start, tool, end);
        List<WorkflowNodeRun> rows = List.of(pending(start), pending(tool), pending(end));
        WorkflowRun run = run("SUCCESS", "{\"end\":{\"answer\":\"END 汇总文本\"}}", null);
        when(runs.runFromWorkbench(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(streams(graph, rows, run,
                        step(tool, "SUCCESS", "{\"rows\":[1,2,3]}")));

        WorkflowTargetRunner.WorkflowTargetResult result = adapter.run(
                intent(), spec(), "取一下数", 7L, 53L, DEADLINE, progress);

        // 工具节点输出是 JSON 原文，不进对话正文；收尾落到 run 的终答
        assertEquals("END 汇总文本", progress.last().answer());
        assertEquals("END 汇总文本", result.turn().getAssistantText());
        // 工具节点仍然出现在时间轴里（它是一步真实执行）
        assertTrue(progress.last().nodes().stream().anyMatch(node -> "TOOL".equals(node.type())));
    }

    /**
     * 用「按真实顺序回调观察者」的方式模拟一次同步执行：开局铺图 → 逐个节点完成 → 返回 run。
     *
     * @param graph 图节点（含 START / END）
     * @param rows  初始运行态行
     * @param run   收尾读回的 run
     * @param steps 依次完成的节点及其运行态行
     * @return Mockito 应答
     */
    private static Answer<WorkflowWorkbenchOutcome> streams(List<WorkflowNode> graph, List<WorkflowNodeRun> rows,
                                                            WorkflowRun run, Step... steps) {
        return invocation -> {
            WorkflowRunListener listener = invocation.getArgument(4);
            listener.runStarted(graph, rows);
            for (Step step : steps) {
                listener.nodeChanged(step.node(), step.row());
            }
            return new WorkflowWorkbenchOutcome(run, false);
        };
    }

    /**
     * 造一个图节点。
     *
     * @param id   节点主键（时间轴按它去重）
     * @param code 业务编码
     * @param name 展示名
     * @param type START / AGENT / TOOL / END
     * @return 节点定义
     */
    private static WorkflowNode node(String id, String code, String name, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setCode(code);
        node.setName(name);
        node.setType(type);
        return node;
    }

    private static WorkflowNodeRun pending(WorkflowNode node) {
        return row(node.getId(), "PENDING", null);
    }

    private static WorkflowNodeRun row(String nodeId, String status, String outputJson) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeId(nodeId);
        row.setNodeCode(nodeId);
        row.setStatus(status);
        row.setOutputJson(outputJson);
        return row;
    }

    /**
     * 一次节点完成：定义与运行态行成对，和引擎的回调口径一致。
     *
     * @param node       节点定义
     * @param status     运行态
     * @param outputJson 节点输出；抽不出正文时当作「这一步没有正文」
     * @return 成对的回调入参
     */
    private static Step step(WorkflowNode node, String status, String outputJson) {
        return new Step(node, row(node.getId(), status, outputJson));
    }

    private static WorkflowRun run(String status, String outputJson, String errorSummary) {
        WorkflowRun run = new WorkflowRun();
        run.setId(1L);
        run.setWorkflowId(88L);
        run.setStatus(status);
        run.setOutputJson(outputJson);
        run.setErrorSummary(errorSummary);
        run.setReleaseVersion(3);
        return run;
    }

    private static WorkflowRunSpec spec() {
        return new WorkflowRunSpec(88L, "policy_brief", "制度要点与报告", "按公司制度整理知识要点", 900L);
    }

    /** 子意图：子问为空，adapter 应回落到用户原文（本用例只关心卡片，不关心入参取哪一份）。 */
    private static RoutedIntent intent() {
        return new RoutedIntent(null, null, null);
    }

    /** 一次节点完成：定义 + 运行态行。 */
    private record Step(WorkflowNode node, WorkflowNodeRun row) {
    }

    /**
     * 记录型进度出口：只关心工作流卡片快照的到达顺序与内容。
     *
     * <p>其余回调（思考 / 工具 / 正文）本用例不关心，全部空实现——它们属于别的用例。
     */
    private static final class RecordingProgress implements TrialProgress {

        private final List<WorkflowRunView> views = new ArrayList<>();

        @Override
        public void phase(String phase) {
        }

        @Override
        public void route(String summary) {
        }

        @Override
        public void reasoning(String delta) {
        }

        @Override
        public void tool(String name, String arguments, String result, boolean success) {
        }

        @Override
        public void assistant(String delta) {
        }

        @Override
        public void workflow(WorkflowRunView view) {
            views.add(view);
        }

        private WorkflowRunView last() {
            assertFalse(views.isEmpty(), "至少要有一份卡片快照");
            return views.get(views.size() - 1);
        }
    }
}
