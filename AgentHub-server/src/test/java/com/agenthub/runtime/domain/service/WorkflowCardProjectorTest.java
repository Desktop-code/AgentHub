package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流卡片投影的「对话停等」用例。所属域：runtime 的 domain。
 *
 * <p>钉住本刀最关键的一条展示口径：<b>等待回复的 AGENT 节点，它产出的问句必须进卡片正文</b>。
 * 以前只认 SUCCESS，问句就只留在自动化执行流的节点列表里，对话正文一片空白——
 * 用户看到的正是「执行流里询问已完成，对话里没有提问」。
 *
 * <p>全部内存对象，不连库、不出站。
 */
class WorkflowCardProjectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 等待回复的 AGENT 节点：问句进正文，节点状态是等待而不是已完成。 */
    @Test
    void waitingReplyNodePutsQuestionIntoAnswer() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        List<WorkflowNode> graph = List.of(node("n1", "start", "开始", "START"), ask,
                node("n5", "end", "结束", "END"));

        projector.runStarted(graph, List.of(pending("n1"), pending("n2"), pending("n5")));
        WorkflowNodeRun waiting = row("n2", "WAITING_REPLY",
                "{\"text\":\"请选择实现语言：Python / Java / TypeScript\",\"question\":\"请选择实现语言\"}");
        projector.nodeChanged(ask, waiting);

        // 等待这一拍就已经能看到问句：对话正文按节点产出增量追加
        WorkflowRunView running = progress.last();
        assertEquals("RUNNING", running.status());
        assertTrue(running.answer().contains("请选择实现语言"), running.answer());
        assertEquals(1, running.nodes().size());
        assertEquals("WAITING_REPLY", running.nodes().get(0).status());
        assertEquals("等待你的回复", running.nodes().get(0).note());
    }

    /** 挂起收口：等待提示接在已流出问句后面，不清空正文。 */
    @Test
    void finishKeepsQuestionAndAppendsWaitingHint() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(node("n1", "start", "开始", "START"), ask),
                List.of(pending("n1"), pending("n2")));
        projector.nodeChanged(ask, row("n2", "WAITING_REPLY",
                "{\"text\":\"请选择实现语言：Python / Java / TypeScript\"}"));

        WorkflowRun run = new WorkflowRun();
        run.setStatus("WAITING_REPLY");
        WorkflowRunView view = projector.finish(run, "工作流「小游戏研发」正在等待你的回复；请直接在本会话下方输入框接着说。");

        assertTrue(view.answer().startsWith("请选择实现语言"), view.answer());
        assertTrue(view.answer().contains("等待你的回复"), view.answer());
        assertEquals("WAITING_REPLY", view.status());
    }

    /** 续跑：上一轮已成功的节点不再重复追加，新完成的节点接在上一轮正文后面。 */
    @Test
    void resumeAppendsAfterPreviousAnswerWithoutRepeating() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON, true, "请选择实现语言：Python / Java / TypeScript");
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        WorkflowNode impl = node("n4", "impl", "游戏实现", "AGENT");
        List<WorkflowNode> graph = List.of(node("n1", "start", "开始", "START"), ask, impl);
        // 开跑时：询问节点已经被续跑入口收成 SUCCESS，实现节点还在 PENDING
        projector.runStarted(graph, List.of(
                row("n1", "SUCCESS", "{}"),
                row("n2", "SUCCESS", "{\"text\":\"请选择实现语言：Python / Java / TypeScript\"}"),
                pending("n4")));

        // 开跑这一拍：保留上一轮问句，但不重复追加
        assertEquals("请选择实现语言：Python / Java / TypeScript", progress.last().answer());

        projector.nodeChanged(impl, row("n4", "SUCCESS", "{\"text\":\"已按 Python 写好单页游戏\"}"));
        assertEquals("请选择实现语言：Python / Java / TypeScript\n\n已按 Python 写好单页游戏",
                progress.last().answer());
    }

    /** 普通（非续跑）轮：老行为不变，SUCCESS 的 AGENT 节点照常增量追加。 */
    @Test
    void normalRunStillStreamsPerFinishedAgent() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("制度要点与报告", "policy_brief",
                progress, JSON);
        WorkflowNode first = node("a1", "policy_points", "知识问答", "AGENT");
        WorkflowNode second = node("a2", "minutes_guide", "纪要写法", "AGENT");
        projector.runStarted(List.of(node("s", "start", "开始", "START"), first, second),
                List.of(pending("s"), pending("a1"), pending("a2")));

        projector.nodeChanged(first, row("a1", "SUCCESS", "{\"policy_points\":{\"answer\":\"第一段要点\"}}"));
        projector.nodeChanged(second, row("a2", "SUCCESS", "{\"minutes_guide\":{\"answer\":\"第二段写法\"}}"));

        assertEquals("第一段要点\n\n第二段写法", progress.last().answer());
        assertFalse(progress.last().answer().contains("请选择实现语言"));
    }

    /** 等待回复的节点：即使配了自定义出参映射、产出里没有常见答案键，也要用 question 兜住问句。 */
    @Test
    void waitingReplyNodeFallsBackToQuestionWhenOutputMappingIsCustom() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(node("n1", "start", "开始", "START"), ask),
                List.of(pending("n1"), pending("n2")));

        // 自定义出参映射：只有 askText，没有 answer / text / question 之外的常见键
        projector.nodeChanged(ask, row("n2", "WAITING_REPLY",
                "{\"askText\":\"请选择实现语言\",\"question\":\"请选择实现语言：Python / Java\"}"));

        assertEquals("请选择实现语言：Python / Java", progress.last().answer());
    }

    /** 等待回复的询问带选项：卡片同拍带 chips；收口为等待时仍带，成功 / 中断则不带。 */
    @Test
    void waitingReplyCarriesChoiceOnlyWhileWaiting() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(node("n1", "start", "开始", "START"), ask),
                List.of(pending("n1"), pending("n2")));

        projector.nodeChanged(ask, row("n2", "WAITING_REPLY",
                "{\"text\":\"请选择实现语言\",\"question\":\"请选择实现语言\","
                        + "\"choice\":{\"question\":\"你想用哪种语言？\","
                        + "\"options\":[{\"id\":\"python\",\"label\":\"Python\"},"
                        + "{\"id\":\"java\",\"label\":\"Java\"}]}}"));

        // 直播这一拍就带选项：与问句一起出现
        WorkflowRunView running = progress.last();
        assertEquals("RUNNING", running.status());
        assertTrue(running.answer().contains("请选择实现语言"));
        assertEquals("你想用哪种语言？", running.choice().question());
        assertEquals(2, running.choice().options().size());
        assertEquals("python", running.choice().options().get(0).id());

        // 收口为等待：仍带选项
        WorkflowRun waiting = new WorkflowRun();
        waiting.setStatus("WAITING_REPLY");
        assertNotNull(projector.finish(waiting, "正在等待你的回复").choice());

        // 成功：不带选项（已经选过 / 流程已往下走）
        WorkflowRun done = new WorkflowRun();
        done.setStatus("SUCCESS");
        assertNull(projector.finish(done, "已完成").choice());
    }

    /** 续跑：上一轮问句保留，但选项不再展示（用户已经选完）。 */
    @Test
    void resumeDropsPreviousChoice() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON, true, "请选择实现语言");
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(node("n1", "start", "开始", "START"), ask),
                List.of(row("n1", "SUCCESS", "{}"),
                        row("n2", "SUCCESS",
                                "{\"text\":\"请选择实现语言\",\"choice\":{\"question\":\"你想用哪种语言？\","
                                        + "\"options\":[{\"id\":\"python\",\"label\":\"Python\"},"
                                        + "{\"id\":\"java\",\"label\":\"Java\"}]}}")));

        // 续跑开跑：正文保留上一轮问句，选项清空
        assertEquals("请选择实现语言", progress.last().answer());
        assertNull(progress.last().choice());
    }

    /** 选项结构不合格（只有 1 项 / id 重复）：整份丢弃，不出半组按钮。 */
    @Test
    void invalidChoiceIsDropped() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(ask), List.of(pending("n2")));

        projector.nodeChanged(ask, row("n2", "WAITING_REPLY",
                "{\"text\":\"问一句\",\"choice\":{\"question\":\"选哪个？\","
                        + "\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"a\",\"label\":\"又A\"}]}}"));

        assertNull(progress.last().choice());
    }

    /** 续跑：用户回复挂到被答复的询问节点上，其它节点不带该字段。 */
    @Test
    void resumedReplyIsAttachedOnlyToAskNode() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON, true, "请选择实现语言", "HTML/CSS/JavaScript 单页");
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        WorkflowNode impl = node("n4", "impl", "游戏实现", "AGENT");
        projector.runStarted(List.of(node("n1", "start", "开始", "START"), ask, impl),
                List.of(row("n1", "SUCCESS", "{}"),
                        row("n2", "SUCCESS",
                                "{\"text\":\"请选择实现语言\",\"choice\":{\"question\":\"你想用哪种语言？\","
                                        + "\"options\":[{\"id\":\"html\",\"label\":\"HTML/CSS/JavaScript 单页\"},"
                                        + "{\"id\":\"java\",\"label\":\"Java\"}]}}"),
                        pending("n4")));

        WorkflowRunView.Node askNode = progress.last().nodes().stream()
                .filter(node -> "ask".equals(node.code())).findFirst().orElseThrow();
        assertEquals("SUCCESS", askNode.status());
        assertEquals("HTML/CSS/JavaScript 单页", askNode.reply());
        WorkflowRunView.Node implNode = progress.last().nodes().stream()
                .filter(node -> "impl".equals(node.code())).findFirst().orElseThrow();
        assertEquals("", implNode.reply(), "非询问节点不显示用户回复");
    }

    /** 出站契约：回复必须真的写进卡片 JSON，否则前端即便投影出来也拿不到「您的选择」。 */
    @Test
    void replyIsSerializedIntoCardJson() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON, true, "请选择实现语言", "HTML/CSS/JavaScript 单页");
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(ask), List.of(row("n2", "SUCCESS",
                "{\"text\":\"请选择实现语言\",\"choice\":{\"question\":\"你想用哪种语言？\","
                        + "\"options\":[{\"id\":\"html\",\"label\":\"HTML/CSS/JavaScript 单页\"},"
                        + "{\"id\":\"java\",\"label\":\"Java\"}]}}")));

        Map<String, Object> payload = WorkflowRunJson.payload(progress.last());
        List<?> nodes = (List<?>) payload.get("nodes");
        Map<?, ?> askNode = (Map<?, ?>) nodes.get(0);
        // 落库事件与 SSE 帧共用本序列化，这里断言等于同时钉住两条通道
        assertEquals("HTML/CSS/JavaScript 单页", askNode.get("reply"));
    }

    /** 首次发起（构造时没有 reply）：即使节点产出里有 choice，也不带回复行。 */
    @Test
    void firstRunHasNoReplyOnAskNode() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode ask = node("n2", "ask", "询问实现语言", "AGENT");
        projector.runStarted(List.of(ask), List.of(row("n2", "SUCCESS",
                "{\"text\":\"请选择实现语言\",\"choice\":{\"question\":\"你想用哪种语言？\","
                        + "\"options\":[{\"id\":\"java\",\"label\":\"Java\"},"
                        + "{\"id\":\"python\",\"label\":\"Python\"}]}}")));

        assertEquals("", progress.last().nodes().get(0).reply());
    }

    /** 同一节点的两轮都留在卡片上，正文两段都在，不互相覆盖。 */
    @Test
    void sameNodeKeepsBothAttempts() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("改写", "revise", progress, JSON);
        WorkflowNode agent = node("n2", "rewrite", "改写", "AGENT");
        WorkflowNodeRun first = row("n2", "SUCCESS", "{\"text\":\"第一轮正文\"}");
        first.setNodeCode("rewrite");
        first.setAttempt(1);
        WorkflowNodeRun second = row("n2", "SUCCESS", "{\"text\":\"第二轮正文\"}");
        second.setNodeCode("rewrite");
        second.setAttempt(2);

        projector.nodeChanged(agent, first);
        projector.nodeChanged(agent, second);

        WorkflowRunView view = progress.last();
        assertEquals(2, view.nodes().size());
        assertEquals(1, view.nodes().get(0).attempt());
        assertEquals(2, view.nodes().get(1).attempt());
        assertTrue(view.nodes().get(0).output().contains("第一轮正文"));
        assertTrue(view.nodes().get(1).output().contains("第二轮正文"));
        assertTrue(view.answer().contains("第一轮正文"), view.answer());
        assertTrue(view.answer().contains("第二轮正文"), view.answer());
    }

    /**
     * 循环：体节点带所属循环编码，循环节点带自己的轮次 / 总次数 / 最近判定。
     *
     * <p>这几项只用于前端把体节点圈进一个框、并写清「第 N/M 轮」，不参与调度。
     */
    @Test
    void loopNodesCarryGroupAndRoundForCard() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode impl = node("n2", "impl", "实现", "AGENT");
        WorkflowNode loop = node("n1", "loop", "循环", "LOOP");
        WorkflowNode review = node("n3", "review", "点评", "AGENT");
        Map<String, List<WorkflowEdge>> outgoing = Map.of(
                "n1", List.of(edge("e1", "n1", "n2", "LOOP_BODY"), edge("e4", "n1", "n3", "LOOP_EXIT")),
                "n2", List.of(edge("e3", "n2", "n1", "LOOP_BACK")));

        projector.runStarted(List.of(loop, impl, review), List.of(pending("n1"), pending("n2")), outgoing);
        WorkflowNodeRun loopRow = row("n1", "SUCCESS",
                "{\"iteration\":2,\"maxIterations\":3,\"decision\":\"BODY\"}");
        loopRow.setNodeCode("loop");
        projector.nodeChanged(loop, loopRow);
        WorkflowNodeRun implRow = row("n2", "RUNNING", null);
        implRow.setNodeCode("impl");
        implRow.setAttempt(2);
        projector.nodeChanged(impl, implRow);

        WorkflowRunView view = progress.last();
        WorkflowRunView.Node loopNode = view.nodes().stream()
                .filter(node -> "loop".equals(node.code())).findFirst().orElseThrow();
        assertEquals(2, loopNode.loopRound());
        assertEquals(3, loopNode.loopTotal());
        assertEquals("BODY", loopNode.loopDecision());
        assertEquals("", loopNode.group(), "循环节点自己不在自己的循环体里");

        WorkflowRunView.Node implNode = view.nodes().stream()
                .filter(node -> "impl".equals(node.code())).findFirst().orElseThrow();
        assertEquals("loop", implNode.group(), "体节点应带上所属循环编码");
        assertEquals(2, implNode.attempt());

        WorkflowRunView.Node reviewNode = view.nodes().stream()
                .filter(node -> "review".equals(node.code())).findFirst().orElseThrow();
        assertEquals("", reviewNode.group(), "循环外的下游节点不属于任何循环体");
    }

    /**
     * 多轮按真实开始时刻排列：第 2 轮落在第 1 轮之后，不会被下游节点挤到前面。
     */
    @Test
    void loopAttemptsAreOrderedByStartTime() {
        RecordingProgress progress = new RecordingProgress();
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev",
                progress, JSON);
        WorkflowNode impl = node("n2", "impl", "实现", "AGENT");
        WorkflowNode review = node("n3", "review", "点评", "AGENT");
        Map<String, List<WorkflowEdge>> outgoing = Map.of(
                "n1", List.of(edge("e1", "n1", "n2", "LOOP_BODY"), edge("e4", "n1", "n3", "LOOP_EXIT")),
                "n2", List.of(edge("e3", "n2", "n1", "LOOP_BACK")));
        WorkflowNode loop = node("n1", "loop", "循环", "LOOP");
        projector.runStarted(List.of(loop, impl, review), List.of(pending("n1"), pending("n2")), outgoing);

        WorkflowNodeRun first = row("n2", "SUCCESS", null);
        first.setNodeCode("impl");
        first.setAttempt(1);
        first.setStartTime(LocalDateTime.of(2026, 11, 25, 10, 0, 0));
        projector.nodeChanged(impl, first);
        WorkflowNodeRun reviewRow = row("n3", "SUCCESS", null);
        reviewRow.setNodeCode("review");
        reviewRow.setStartTime(LocalDateTime.of(2026, 11, 25, 10, 5, 0));
        projector.nodeChanged(review, reviewRow);
        // 第 2 轮在点评之后才开始：库里是后插入的行，展示上仍要排在第 1 轮之后
        WorkflowNodeRun second = row("n2", "RUNNING", null);
        second.setNodeCode("impl");
        second.setAttempt(2);
        second.setStartTime(LocalDateTime.of(2026, 11, 25, 10, 9, 0));
        projector.nodeChanged(impl, second);

        List<String> order = progress.last().nodes().stream()
                .filter(node -> "AGENT".equals(node.type()))
                .map(node -> node.code() + "#" + node.attempt()).toList();
        assertEquals(List.of("impl#1", "review#1", "impl#2"), order,
                "第 2 轮应排在第 1 轮与下游之后，而不是被下游挤到最前");
    }

    /**
     * 没有会话事件时，按库里的节点行现算：问句、选项、回复、循环框与直播同一形状。
     */
    @Test
    void persistedViewUsesStoredOutputChoiceReplyAndLoop() {
        WorkflowCardProjector projector = new WorkflowCardProjector("小游戏研发", "mini_game_dev", null, JSON);
        WorkflowNode loop = node("n1", "loop", "循环", "LOOP");
        WorkflowNode ask = node("n2", "ask", "询问", "AGENT");
        WorkflowNode review = node("n3", "review", "点评", "AGENT");
        Map<String, List<WorkflowEdge>> outgoing = Map.of(
                "n1", List.of(edge("e1", "n1", "n2", "LOOP_BODY"), edge("e4", "n1", "n3", "LOOP_EXIT")),
                "n2", List.of(edge("e3", "n2", "n1", "LOOP_BACK")));
        WorkflowNodeRun loopRow = row("n1", "SUCCESS",
                "{\"iteration\":1,\"maxIterations\":3,\"decision\":\"BODY\"}");
        loopRow.setNodeCode("loop");
        WorkflowNodeRun askRow = row("n2", "WAITING_REPLY",
                "{\"text\":\"请选择实现语言\",\"question\":\"你想用哪种语言？\","
                        + "\"choice\":{\"question\":\"你想用哪种语言？\","
                        + "\"options\":[{\"id\":\"java\",\"label\":\"Java\"},"
                        + "{\"id\":\"python\",\"label\":\"Python\"}]}}");
        askRow.setNodeCode("ask");
        askRow.setReplyText("Python");
        WorkflowRun run = new WorkflowRun();
        run.setId(9L);
        run.setStatus("WAITING_REPLY");

        WorkflowRunView view = projector.persisted(run, List.of(loop, ask, review), outgoing,
                List.of(loopRow, askRow), "请在下方接着说");

        assertEquals("WAITING_REPLY", view.status());
        assertEquals(9L, view.runId());
        assertNotNull(view.choice());
        assertEquals(2, view.choice().options().size());
        assertTrue(view.answer().contains("请选择实现语言") || view.answer().contains("哪种语言"), view.answer());
        WorkflowRunView.Node askNode = view.nodes().stream()
                .filter(node -> "ask".equals(node.code())).findFirst().orElseThrow();
        assertEquals("WAITING_REPLY", askNode.status());
        assertTrue(askNode.output().contains("哪种语言") || askNode.output().contains("请选择实现语言"),
                askNode.output());
        assertEquals("Python", askNode.reply());
        assertEquals("loop", askNode.group());
        WorkflowRunView.Node loopNode = view.nodes().stream()
                .filter(node -> "loop".equals(node.code())).findFirst().orElseThrow();
        assertEquals(1, loopNode.loopRound());
        assertEquals(3, loopNode.loopTotal());
        assertEquals("", view.nodes().stream().filter(node -> "review".equals(node.code()))
                .findFirst().orElseThrow().group());
    }

    private static WorkflowEdge edge(String id, String source, String target, String type) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setType(type);
        return edge;
    }

    private static WorkflowNode node(String id, String code, String name, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setCode(code);
        node.setName(name);
        node.setType(type);
        return node;
    }

    private static WorkflowNodeRun pending(String nodeId) {
        return row(nodeId, "PENDING", null);
    }

    private static WorkflowNodeRun row(String nodeId, String status, String outputJson) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeId(nodeId);
        row.setNodeCode(nodeId);
        row.setStatus(status);
        row.setOutputJson(outputJson);
        return row;
    }

    /** 只记卡片快照的进度出口；其它回调本用例不关心。 */
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
            return views.get(views.size() - 1);
        }
    }
}
