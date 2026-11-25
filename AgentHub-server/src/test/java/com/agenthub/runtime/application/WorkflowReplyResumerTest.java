package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「下一句送回同一条运行」的 runtime 接线用例。所属域：runtime 的 application。
 *
 * <p>钉住三件不可退让的事（全部 mock，不连库、不出站、不打模型）：
 * <ol>
 *   <li>本会话没有等待中的运行时不产生任何副作用（不写事件、不调续跑）——现网行为完全不变；</li>
 *   <li>等待中的运行时：回复交给工作流域续跑入口，<b>不新建 run</b>；</li>
 *   <li>落库顺序是「身份 begin → 工作流卡 → 助手正文」，与首次发起同序，
 *       否则回放挂不上卡、或刷新后一直被当成没写完。</li>
 * </ol>
 */
class WorkflowReplyResumerTest {

    private WorkflowRunAppService workflowRuns;
    private TrialEventWriter writer;
    private com.agenthub.runtime.domain.repository.RunEventRepository events;
    private WorkflowReplyResumer resumer;

    @BeforeEach
    void setUp() {
        workflowRuns = mock(WorkflowRunAppService.class);
        writer = mock(TrialEventWriter.class);
        events = mock(com.agenthub.runtime.domain.repository.RunEventRepository.class);
        resumer = new WorkflowReplyResumer(workflowRuns, writer, events, new ObjectMapper(),
                mock(com.agenthub.audit.domain.OperLogWriter.class));
        when(events.nextSeq(anyLong())).thenReturn(10, 11, 12, 13, 14);
        // 两个重载都要桩：6 参用于「摘要=正文」（助手正文），7 参用于摘要与正文不同的卡片帧
        when(writer.append(anyLong(), any(Integer.class), any(EventType.class), anyString(), any(), any(), any()))
                .thenAnswer(WorkflowReplyResumerTest::toEvent);
        when(writer.append(anyLong(), any(Integer.class), any(EventType.class), anyString(), any(), any()))
                .thenAnswer(WorkflowReplyResumerTest::toEvent);
    }

    /**
     * 把一次落库调用收成最小事件：只保留用例要断言的三个字段。
     *
     * @param invocation Mockito 调用
     * @return 事件
     */
    private static RunEvent toEvent(org.mockito.invocation.InvocationOnMock invocation) {
        RunEvent event = new RunEvent();
        event.setSessionId(invocation.getArgument(0));
        event.setSeq(invocation.getArgument(1));
        event.setEventType(((EventType) invocation.getArgument(2)).code());
        return event;
    }

    /** 没有等待中的运行：不调续跑入口、不写事件，调用方按普通一轮继续。 */
    @Test
    @DisplayName("无等待运行：零副作用")
    void noWaitingRunLeavesNoTrace() {
        when(workflowRuns.findWaitingReplyRun(66L)).thenReturn(Optional.empty());

        assertFalse(resumer.hasWaitingReply(66L));
        assertTrue(resumer.resumeIfWaiting(66L, "Python", 7L, null).isEmpty());
        verify(workflowRuns, never()).resumeWithReply(anyLong(), anyString(), anyLong(), any());
        verify(writer, never()).append(anyLong(), any(Integer.class), any(EventType.class), anyString(),
                any(), any(), any());
    }

    /** 等待中的运行：回复交给续跑入口，事件按「身份 → 卡片 → 正文」落库。 */
    @Test
    @DisplayName("等待运行：回复送回同一条 run，事件按身份/卡片/正文落库")
    void resumesWaitingRunAndWritesTimeline() {
        WorkflowRun waiting = waitingRun();
        when(workflowRuns.findWaitingReplyRun(66L)).thenReturn(Optional.of(waiting));
        when(workflowRuns.definitionOf(waiting)).thenReturn(Optional.of(def()));
        when(workflowRuns.resumeWithReply(eq(9L), eq("Python"), eq(7L), any(WorkflowRunListener.class)))
                .thenAnswer(invocation -> {
                    // 续跑后引擎继续跑完：这里模拟「问句节点已过、实现节点产出正文」
                    WorkflowRunListener listener = invocation.getArgument(3);
                    WorkflowNode ask = node("n2", "ask", "询问实现语言");
                    WorkflowNode impl = node("n4", "impl", "游戏实现");
                    listener.runStarted(List.of(node("n1", "start", "开始"), ask, impl),
                            List.of(row("n1", "SUCCESS"), row("n2", "SUCCESS"), row("n4", "PENDING")));
                    listener.nodeChanged(impl, row("n4", "SUCCESS"));
                    WorkflowRun done = waitingRun();
                    done.setStatus("SUCCESS");
                    done.setOutputJson("{\"end\":{\"answer\":\"已按 Python 写好单页游戏\"}}");
                    WorkflowRunDetail detail = new WorkflowRunDetail();
                    detail.setRun(done);
                    return detail;
                });

        Optional<WorkflowReplyResumer.Resumed> resumed = resumer.resumeIfWaiting(66L, "Python", 7L, null);

        assertTrue(resumed.isPresent());
        List<RunEvent> written = resumed.get().events();
        assertEquals(3, written.size(), "身份、卡片、正文三条事件");
        assertEquals("participant", written.get(0).getEventType());
        assertEquals("workflow", written.get(1).getEventType());
        assertEquals("assistant", written.get(2).getEventType());
        // 卡片正文取自己已流出的那段，与回放同一份
        assertTrue(resumed.get().turn().getAssistantText().contains("已按 Python 写好单页游戏"),
                resumed.get().turn().getAssistantText());
        // 不新建 run、也不经过入口判路由
        verify(workflowRuns, never()).runFromWorkbench(any(), anyString(), anyLong(), anyLong(), any());
    }

    /**
     * 续跑卡片不再带上上一轮的「正在等待你的回复」。
     *
     * <p>上一轮收口时平台把等待提示追加在问句后面；续跑只是同一条运行的下一段，
     * 若把提示当正文种子，用户会看到节点已 SUCCESS、正文却仍说在等待。
     */
    @Test
    @DisplayName("续跑：上一轮等待提示不跟到新卡片，问句保留")
    void resumeDropsPreviousWaitingHint() {
        WorkflowRun waiting = waitingRun();
        when(workflowRuns.findWaitingReplyRun(66L)).thenReturn(Optional.of(waiting));
        when(workflowRuns.definitionOf(waiting)).thenReturn(Optional.of(def()));

        RunEvent previous = new RunEvent();
        previous.setEventType("workflow");
        previous.setContentText("{\"name\":\"小游戏研发\",\"code\":\"mini_game_dev\","
                + "\"status\":\"WAITING_REPLY\","
                + "\"answer\":\"你想用哪种语言实现？\\n\\n"
                + "工作流「小游戏研发」正在等待你的回复；请直接在本会话下方输入框接着说，"
                + "这句话会送回同一条运行继续往下走。\",\"nodes\":[]}");
        when(events.listBySessionId(66L)).thenReturn(List.of(previous));

        when(workflowRuns.resumeWithReply(eq(9L), eq("Java"), eq(7L), any(WorkflowRunListener.class)))
                .thenAnswer(invocation -> {
                    WorkflowRunListener listener = invocation.getArgument(3);
                    WorkflowNode ask = node("n2", "ask", "询问实现语言");
                    WorkflowNode impl = node("n4", "impl", "游戏实现");
                    listener.runStarted(List.of(ask, impl),
                            List.of(row("n2", "SUCCESS", "{}"), row("n4", "PENDING")));
                    listener.nodeChanged(impl, row("n4", "SUCCESS",
                            "{\"text\":\"已按 Java 写好单页游戏\"}"));
                    WorkflowRun done = waitingRun();
                    done.setStatus("SUCCESS");
                    WorkflowRunDetail detail = new WorkflowRunDetail();
                    detail.setRun(done);
                    return detail;
                });

        Optional<WorkflowReplyResumer.Resumed> resumed = resumer.resumeIfWaiting(66L, "Java", 7L, null);

        assertTrue(resumed.isPresent());
        String answer = resumed.get().turn().getAssistantText();
        // 用户看过的问句要保留，接在后面的新产出也要在
        assertTrue(answer.contains("你想用哪种语言实现？"), answer);
        assertTrue(answer.contains("已按 Java 写好单页游戏"), answer);
        // 等待提示只属于上一轮，不能跟着新卡片再出现
        assertFalse(answer.contains("正在等待你的回复"), answer);
    }

    /** 续跑入口抛业务异常（并发已收口）：按「没有可续的运行」处理，不冒充成功。 */
    @Test
    @DisplayName("并发已收口：返回空，不写半截事件")
    void concurrencyClosureReturnsEmpty() {
        WorkflowRun waiting = waitingRun();
        when(workflowRuns.findWaitingReplyRun(66L)).thenReturn(Optional.of(waiting));
        when(workflowRuns.definitionOf(waiting)).thenReturn(Optional.of(def()));
        when(workflowRuns.resumeWithReply(eq(9L), eq("Python"), eq(7L), any(WorkflowRunListener.class)))
                .thenThrow(new com.agenthub.common.error.BizException(
                        com.agenthub.common.error.ErrorCode.BAD_REQUEST, "这条运行刚刚被取消"));

        assertTrue(resumer.resumeIfWaiting(66L, "Python", 7L, null).isEmpty());
        verify(writer, never()).append(anyLong(), any(Integer.class), any(EventType.class), anyString(),
                any(), any(), any());
    }

    /** 点选项续跑：与输入框同一写入路径，回复文案取服务端登记值。 */
    @Test
    @DisplayName("点选续跑：与输入框共用 resume，写入的是选项文案")
    void resumeWithLabelUsesOptionText() {
        WorkflowRun waiting = waitingRun();
        when(workflowRuns.definitionOf(waiting)).thenReturn(Optional.of(def()));
        when(workflowRuns.resumeWithReply(eq(9L), eq("Python"), eq(7L), any(WorkflowRunListener.class)))
                .thenAnswer(invocation -> {
                    WorkflowRunListener listener = invocation.getArgument(3);
                    WorkflowNode impl = node("n4", "impl", "游戏实现");
                    listener.runStarted(List.of(node("n1", "start", "开始"), impl),
                            List.of(row("n1", "SUCCESS"), row("n4", "PENDING")));
                    listener.nodeChanged(impl, row("n4", "SUCCESS"));
                    WorkflowRun done = waitingRun();
                    done.setStatus("SUCCESS");
                    WorkflowRunDetail detail = new WorkflowRunDetail();
                    detail.setRun(done);
                    return detail;
                });

        Optional<WorkflowReplyResumer.Resumed> resumed = resumer.resumeWithLabel(66L, waiting, "Python", 7L, null);

        assertTrue(resumed.isPresent());
        // 与输入框提交落到同一个方法：input.reply 就是这句选项文案
        verify(workflowRuns).resumeWithReply(eq(9L), eq("Python"), eq(7L), any(WorkflowRunListener.class));
        verify(workflowRuns, never()).runFromWorkbench(any(), anyString(), anyLong(), anyLong(), any());
    }

    /** 等待中点停止：取消那条运行并补「已中断」，不新建 run、不冒成续跑。 */    @Test
    @DisplayName("等待中停止：取消 run 并补中断闭环")
    void stopWaitingCancelsRun() {
        WorkflowRun waiting = waitingRun();
        when(workflowRuns.findWaitingReplyRun(66L)).thenReturn(Optional.of(waiting));
        when(workflowRuns.definitionOf(waiting)).thenReturn(Optional.of(def()));

        assertTrue(resumer.stopWaiting(66L, 7L));

        verify(workflowRuns).cancel(9L);
        // 最后一条闭环是「已中断」：刷新后 hasOpenTurn 据此停止续订
        verify(writer).append(eq(66L), any(Integer.class), eq(EventType.ERROR), eq("已中断"), any(), any());
    }

    /** 没有等待中的运行：停止不产生任何副作用（现网「只能取消未完成会话」的语义不变）。 */
    @Test
    @DisplayName("无等待运行：停止零副作用")
    void stopWaitingNoopWhenNothingWaiting() {
        when(workflowRuns.findWaitingReplyRun(66L)).thenReturn(Optional.empty());

        assertFalse(resumer.stopWaiting(66L, 7L));        verify(workflowRuns, never()).cancel(anyLong());
        verify(writer, never()).append(anyLong(), any(Integer.class), any(EventType.class), anyString(),
                any(), any(), any());
    }

    private static WorkflowRun waitingRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(9L);
        run.setTenantId(1L);
        run.setWorkflowId(88L);
        run.setStatus("WAITING_REPLY");
        run.setWorkbenchSessionId(66L);
        return run;
    }

    private static WorkflowDef def() {
        WorkflowDef def = new WorkflowDef();
        def.setId(88L);
        def.setCode("mini_game_dev");
        def.setName("小游戏研发");
        return def;
    }

    private static WorkflowNode node(String id, String code, String name) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setCode(code);
        node.setName(name);
        node.setType("AGENT");
        return node;
    }

    private static com.agenthub.workflow.domain.model.WorkflowNodeRun row(String nodeId, String status) {
        return row(nodeId, status,
                "SUCCESS".equals(status) ? "{\"text\":\"已按 Python 写好单页游戏\"}" : null);
    }

    /** 指定产出的运行态行：续跑正文种子用例需要区分「询问节点已完成」和「实现节点刚完成」。 */
    private static com.agenthub.workflow.domain.model.WorkflowNodeRun row(
            String nodeId, String status, String outputJson) {
        com.agenthub.workflow.domain.model.WorkflowNodeRun row =
                new com.agenthub.workflow.domain.model.WorkflowNodeRun();
        row.setNodeId(nodeId);
        row.setNodeCode(nodeId);
        row.setStatus(status);
        row.setOutputJson(outputJson);
        return row;
    }
}
