package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
