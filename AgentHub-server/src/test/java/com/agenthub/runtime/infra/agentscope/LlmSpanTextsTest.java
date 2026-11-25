package com.agenthub.runtime.infra.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 入参增量与出参碎片过滤。
 */
class LlmSpanTextsTest {

    @Test
    void inputKeepsDeltaWithoutFullSystemPrompt() {
        Msg system = Msg.builder()
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text("很长的平台前缀不要入库\n\n【业务提示词】\n查天气助手").build())
                .build();
        Msg user = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("北京天气").build()).build();
        String input = LlmSpanTexts.buildInput(List.of(system, user));
        assertTrue(input.contains("平台前缀：已应用"));
        assertTrue(input.contains("北京天气"));
        assertTrue(input.contains("业务提示要点"));
        assertFalse(input.contains("很长的平台前缀不要入库"));
        assertFalse(input.contains("【业务提示词】\n查天气助手"));
    }

    @Test
    void laterRoundOnlyWritesToolFeedback() {
        Msg system = Msg.builder()
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text("前缀\n\n【业务提示词】\n助手").build())
                .build();
        Msg user = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("北京天气").build()).build();
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("调用工具").build()).build();
        Msg tool = Msg.builder().role(MsgRole.TOOL).content(TextBlock.builder().text("晴 26℃").build()).build();
        String input = LlmSpanTexts.buildInput(List.of(system, user, assistant, tool));
        assertTrue(input.contains("平台前缀：已应用"));
        assertTrue(input.contains("晴 26℃"));
        assertFalse(input.contains("北京天气"));
        assertFalse(input.contains("业务提示要点"));
    }

    @Test
    void fragmentToolCallsAreDropped() {
        StringBuilder text = new StringBuilder();
        Map<String, String> tools = LlmSpanTexts.newToolCallMap();
        ToolUseBlock fragment = ToolUseBlock.builder()
                .id("f1")
                .name("__fragment__")
                .input(Map.of("partial", true))
                .build();
        ToolUseBlock real = ToolUseBlock.builder()
                .id("c1")
                .name("queryWeather")
                .input(Map.of("city", "北京"))
                .build();
        LlmSpanTexts.captureOutput(List.of(
                TextBlock.builder().text("先查一下").build(),
                fragment,
                real
        ), text, tools);
        String output = LlmSpanTexts.mergeOutput(text, tools);
        assertTrue(output.contains("先查一下"));
        assertTrue(output.contains("queryWeather"));
        assertFalse(output.contains("__fragment__"));
        assertTrue(LlmSpanTexts.isFragment(fragment));
        assertFalse(LlmSpanTexts.isFragment(real));
    }

    @Test
    void briefForToolAndFinalAnswer() {
        assertTrue(LlmSpanTexts.briefOf("tool_call id=1 name=queryWeather input={city=北京}")
                .contains("决定调用 queryWeather"));
        assertTrue(LlmSpanTexts.briefOf("今天北京晴，26℃").contains("生成终答"));
        assertNull(LlmSpanTexts.briefOf("   "));
    }
}
