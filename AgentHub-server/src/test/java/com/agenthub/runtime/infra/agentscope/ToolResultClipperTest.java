package com.agenthub.runtime.infra.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果回灌截断只改进模型的副本。
 */
class ToolResultClipperTest {

    @Test
    void shortTextUnchanged() {
        Msg msg = toolMsg("short");
        List<Msg> input = List.of(msg);
        assertSame(input, ToolResultClipper.clip(input, 100));
        assertEquals("short", ToolResultClipper.clipText("short", 100));
    }

    @Test
    void longTextIsClippedWithinLimit() {
        String body = "a".repeat(50);
        // 上限需能装下截断标记 + 一段正文，验收：长度 ≤ 上限且含 totalChars
        int max = 40;
        String clipped = ToolResultClipper.clipText(body, max);
        assertTrue(clipped.length() <= max);
        assertTrue(clipped.contains("truncated"));
        assertTrue(clipped.contains("totalChars=50"));
        assertEquals(50, body.length());
    }

    @Test
    void longJsonIsCompactedThenClipped() {
        // 美化 JSON：compact 后仍超上限，硬截并带 totalChars
        String pretty = "{\n  \"city\": \"" + "x".repeat(80) + "\",\n  \"ok\": true\n}";
        int max = 48;
        String clipped = ToolResultClipper.clipText(pretty, max);
        assertTrue(clipped.length() <= max);
        assertTrue(clipped.contains("truncated"));
        assertTrue(clipped.contains("totalChars="));
        assertFalse(clipped.contains("\n  \""));
    }

    @Test
    void prettyJsonUnderLimitBecomesCompact() {
        String pretty = "{\n  \"a\": 1\n}";
        String clipped = ToolResultClipper.clipText(pretty, 100);
        assertEquals("{\"a\":1}", clipped);
        assertFalse(clipped.contains("truncated"));
    }

    @Test
    void trimsWhitespaceBeforeLimitCheck() {
        assertEquals("ok", ToolResultClipper.clipText("  ok  ", 100));
    }

    @Test
    void messageListLongTextIsClipped() {
        String body = "b".repeat(50);
        List<Msg> clipped = ToolResultClipper.clip(List.of(toolMsg(body)), 40);
        ToolResultBlock block = (ToolResultBlock) clipped.get(0).getContent().get(0);
        String text = ((TextBlock) block.getOutput().get(0)).getText();
        assertTrue(text.length() <= 40);
        assertTrue(text.contains("truncated"));
        assertTrue(text.contains("totalChars=50"));
    }

    @Test
    void jsonArrayKeepsWholeItemsBeforeHardClip() {
        // 检索命中形态：多条长命中 + 上限只够前两条，必须整条保留并注明省略条数
        String body = "制度原文".repeat(35);
        String item = "{\"content\":\"" + body + "\",\"title\":\"差旅\"}";
        String json = "[" + item + "," + item.replace("差旅", "报销") + ","
                + item.replace("差旅", "考勤") + "," + item.replace("差旅", "用章") + "]";
        String clipped = ToolResultClipper.clipText(json, 600);
        assertTrue(clipped.length() <= 600);
        // 完整条目仍在：标题与正文开头可直接引用
        assertTrue(clipped.contains("\"title\":\"差旅\""));
        assertTrue(clipped.contains("制度原文"));
        assertTrue(clipped.contains("已省略"));
        // 不是硬截：不留半截 JSON 的 truncated 标记
        assertFalse(clipped.contains("truncated"));
    }

    @Test
    void jsonArraySingleHitTooLargeIsShrunkNotBroken() {
        String body = "很长的制度正文".repeat(200);
        String json = "[{\"content\":\"" + body + "\",\"title\":\"考勤\"}]";
        String clipped = ToolResultClipper.clipText(json, 300);
        assertTrue(clipped.length() <= 300);
        assertTrue(clipped.contains("\"title\":\"考勤\""));
        assertTrue(clipped.contains("后略"));
    }

    @Test
    void jsonArrayUnderLimitUntouched() {
        String json = "[{\"content\":\"短\"}]";
        assertEquals(json, ToolResultClipper.clipText(json, 100));
    }

    private Msg toolMsg(String text) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(List.of(new ToolResultBlock("1", "demo",
                        List.of(TextBlock.builder().text(text).build()), null)))
                .build();
    }
}
