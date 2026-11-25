package com.agenthub.model.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 连通摘要不得带出 Key，并且不能超过列宽。
 */
class ProbeSummaryTest {

    /**
     * 明文出现在说明中间时要换成掩码；整段就是 Key 时不能留空串。
     */
    @Test
    void stripsKeyAndCapsLength() {
        String key = "sk-test-1234567890";
        assertEquals("上游返回 *** 无效", ProbeSummary.of("上游返回 " + key + " 无效", key));
        assertEquals("***", ProbeSummary.of(key, key));
        assertEquals("无说明", ProbeSummary.of("  ", key));
        assertEquals("目标不可达", ProbeSummary.of("目标不可达", key));
        String longText = "x".repeat(300) + key;
        String summary = ProbeSummary.of(longText, key);
        assertEquals(255, summary.length());
        assertFalse(summary.contains(key));
    }
}
