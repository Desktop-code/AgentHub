package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.TrialProgress;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentScope 事件映射。正文增量不能当成思考。
 */
class TrialEventCollectorTest {

    /**
     * ThinkingBlock 才写 reasoning，工具结果单独成步。
     */
    @Test
    void mapsThinkingAndTool() {
        List<AgentEvent> events = List.of(
                new ThinkingBlockDeltaEvent("r1", "thinking", "先查天气"),
                new ToolCallStartEvent("r1", "call-1", "weather"),
                new ToolCallDeltaEvent("r1", "call-1", "weather", "{\"city\":\"北京\"}"),
                new ToolResultTextDeltaEvent("r1", "call-1", "weather", "晴"),
                new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS),
                new TextBlockDeltaEvent("r1", "text", "# 今天晴")
        );
        TrialTurn turn = TrialEventCollector.collect(events);
        assertEquals("# 今天晴", turn.getAssistantText());
        assertTrue(TrialEventCollector.hasReasoning(turn.getTraces()));
        assertEquals(EventType.REASONING, turn.getTraces().get(0).getType());
        assertEquals("先查天气", turn.getTraces().get(0).getContent());
        TrialTrace tool = turn.getTraces().get(1);
        assertEquals("weather", tool.getName());
        assertTrue(tool.isSuccess());
        assertTrue(tool.getArguments().contains("北京"));
        assertEquals("晴", tool.getContent());
    }

    /**
     * 工具前的正文是中途说明，只进推理；终答只保留工具之后的那一段，并且只推一次。
     */
    @Test
    void narrationBeforeToolIsNotTheFinalAnswer() {
        List<String> assistants = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        TrialProgress progress = new TrialProgress() {
            @Override
            public void phase(String phase) {
            }

            @Override
            public void route(String summary) {
            }

            @Override
            public void reasoning(String delta) {
                reasons.add(delta);
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
            }

            @Override
            public void assistant(String delta) {
                assistants.add(delta);
            }
        };
        TrialEventCollector collector = new TrialEventCollector(progress, null, true);
        collector.accept(new TextBlockDeltaEvent("r3", "text", "改去官网"));
        assertTrue(assistants.isEmpty(), "工具还没开始，不能把中途说明当成终答推出去");
        collector.accept(new ToolCallStartEvent("r3", "call-1", "browserUse"));
        collector.accept(new TextBlockDeltaEvent("r3", "text", "预约已开放"));
        TrialTurn turn = collector.finish();
        assertEquals("预约已开放", turn.getAssistantText());
        assertEquals(List.of("预约已开放"), assistants);
        assertEquals(List.of("改去官网"), reasons);
        assertEquals("改去官网", turn.getTraces().get(0).getContent());
        assertEquals(EventType.REASONING, turn.getTraces().get(0).getType());
    }

    /**
     * 撞限收尾时，还没跟工具的那段正文留在推理里，不再变成终答。
     */
    @Test
    void pendingNarrationStaysReasoningWhenRoundsHit() {
        TrialEventCollector collector = new TrialEventCollector();
        collector.accept(new TextBlockDeltaEvent("r4", "text", "碰到验证码"));
        collector.keepPendingAsReasoning();
        TrialTurn turn = collector.finish();
        assertEquals("", turn.getAssistantText());
        assertEquals("碰到验证码", turn.getTraces().get(0).getContent());
        assertEquals(EventType.REASONING, turn.getTraces().get(0).getType());
    }

    /**
     * 只有助手正文时不造 reasoning。
     */
    @Test
    void textAloneIsNotReasoning() {
        TrialTurn turn = TrialEventCollector.collect(List.of(
                new TextBlockDeltaEvent("r2", "text", "直接回答")
        ));
        assertEquals("直接回答", turn.getAssistantText());
        assertFalse(TrialEventCollector.hasReasoning(turn.getTraces()));
    }
}
