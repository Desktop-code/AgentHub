package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 假 tool_call 文本必须失败收束；真工具成功后不误伤。
 */
class TextToolCallGuardTest {

    @Test
    void orphanTextToolCallFailsRound() {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("好的，我来查一下\n<tool_call>{\"name\":\"queryWeather\",\"city\":\"北京\"}</tool_call>");
        TrialFailure ex = assertThrows(TrialFailure.class,
                () -> TextToolCallGuard.assertNoOrphanTextToolCall(turn));
        assertTrue(ex.getMessage().contains("未真正执行"));
    }

    @Test
    void successfulToolAllowsFinalAnswer() {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("北京今天晴，气温 22°C。");
        TrialTrace tool = new TrialTrace();
        tool.setType(EventType.TOOL);
        tool.setName("queryWeather");
        tool.setSuccess(true);
        tool.setContent("{\"city\":\"北京\",\"current\":{\"temperatureC\":22}}");
        turn.setTraces(List.of(tool));
        assertDoesNotThrow(() -> TextToolCallGuard.assertNoOrphanTextToolCall(turn));
    }

    @Test
    void normalAnswerWithoutToolCallPasses() {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("这是普通回答，没有工具。");
        assertDoesNotThrow(() -> TextToolCallGuard.assertNoOrphanTextToolCall(turn));
    }
}
