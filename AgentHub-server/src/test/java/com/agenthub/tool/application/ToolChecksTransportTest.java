package com.agenthub.tool.application;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输校验：空/SSE/HTTP 合法；其它拒绝。所属域：tool。
 */
class ToolChecksTransportTest {

    @Test
    void blankDefaultsToSse() {
        assertEquals("SSE", ToolChecks.transport(null));
        assertEquals("SSE", ToolChecks.transport(""));
        assertEquals("SSE", ToolChecks.transport("sse"));
    }

    @Test
    void acceptsHttpCaseInsensitive() {
        assertEquals("HTTP", ToolChecks.transport("HTTP"));
        assertEquals("HTTP", ToolChecks.transport("http"));
    }

    @Test
    void rejectsIllegalTransport() {
        BizException ex = assertThrows(BizException.class, () -> ToolChecks.transport("stdio"));
        assertTrue(ex.getMessage().contains("SSE") || ex.getMessage().contains("HTTP"));
    }

    @Test
    void rejectsEmptyBaseUrl() {
        BizException ex = assertThrows(BizException.class, () -> ToolChecks.baseUrl("  "));
        assertTrue(ex.getMessage().contains("不能为空"));
    }
}
