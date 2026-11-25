package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 视觉输出上限取值规则。所属域：runtime。
 * 守两件事：未配置时默认 1024（多数视觉模型的输出区间就是 [1,1024]，2048 会被 400）；
 * 显式配更大值时原样发出（平台不做上限钳制）。
 */
class VisionMaxTokensTest {

    @Test
    void defaultIs1024() {
        assertEquals(1024, VisionMaxTokens.DEFAULT);
        assertEquals(1024, VisionMaxTokens.resolve(null));
        // 0 / 负数在上游一律被拒，按「没配」处理
        assertEquals(1024, VisionMaxTokens.resolve(0));
        assertEquals(1024, VisionMaxTokens.resolve(-1));
    }

    @Test
    void explicitValueIsNotClamped() {
        assertEquals(4096, VisionMaxTokens.resolve(4096));
        assertEquals(2048, VisionMaxTokens.resolve(2048));
        assertEquals(1, VisionMaxTokens.resolve(1));
        assertEquals(2000, VisionMaxTokens.resolve(2000), "超过 1024 也不得被钳制");
    }

    @Test
    void parsesBothKeyStyles() {
        assertEquals(512, VisionMaxTokens.parse("{\"max_tokens\":512}"));
        assertEquals(4096, VisionMaxTokens.parse("{\"maxTokens\":4096}"));
        // 手填 JSON 常把数字写成字符串
        assertEquals(768, VisionMaxTokens.parse("{\"max_tokens\":\"768\"}"));
    }

    @Test
    void ignoresMissingOrInvalidConfig() {
        assertNull(VisionMaxTokens.parse(null));
        assertNull(VisionMaxTokens.parse(""));
        assertNull(VisionMaxTokens.parse("{\"temperature\":0.7}"));
        assertNull(VisionMaxTokens.parse("{\"max_tokens\":0}"));
        assertNull(VisionMaxTokens.parse("{\"max_tokens\":\"many\"}"));
        assertNull(VisionMaxTokens.parse("not-json"));
        assertNull(VisionMaxTokens.parse("[1,2]"));
    }
}
