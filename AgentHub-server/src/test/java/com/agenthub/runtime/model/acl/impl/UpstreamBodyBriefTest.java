package com.agenthub.runtime.model.acl.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游非 2xx 响应体摘要。所属域：runtime（ACL 实现）。
 * 守的是「400 只剩状态码」这个排障缺口：原因要可读，Key 与长 base64 不许出现。
 */
class UpstreamBodyBriefTest {

    @Test
    void picksErrorMessageFromJson() {
        String brief = UpstreamBodyBrief.of(
                "{\"error\":{\"message\":\"image too large\",\"type\":\"invalid_request_error\","
                        + "\"code\":\"invalid_parameter\"}}");
        assertTrue(brief.contains("image too large"), brief);
        assertTrue(brief.contains("invalid_parameter") || brief.contains("invalid_request_error"), brief);
    }

    @Test
    void stripsKeysAndLongBase64() {
        String brief = UpstreamBodyBrief.of("{\"error\":{\"message\":\"bad\"},"
                + "\"authorization\":\"Bearer sk-abcdef123456\","
                + "\"image\":\"data:image/png;base64," + "A".repeat(300) + "\"}");
        assertFalse(brief.contains("sk-abcdef123456"), brief);
        assertFalse(brief.contains("A".repeat(50)), brief);
    }

    @Test
    void emptyAndPlainTextBodies() {
        assertEquals("", UpstreamBodyBrief.of(null));
        assertEquals("", UpstreamBodyBrief.of("   "));
        assertEquals("bad gateway", UpstreamBodyBrief.of("bad gateway"));
    }

    @Test
    void exceptionMessageCarriesStatusAndBrief() {
        UpstreamHttpException error = new UpstreamHttpException(400, "{\"error\":{\"message\":\"mime not supported\"}}");
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("HTTP 400"), error.getMessage());
        assertTrue(error.getMessage().contains("mime not supported"), error.getMessage());
        assertFalse(error.getMessage().contains("（含响应体）"), "不再输出无信息量的占位");

        UpstreamHttpException bare = new UpstreamHttpException(500, "");
        assertNull(bare.bodyBrief());
        assertEquals("上游返回 HTTP 500", bare.getMessage());
    }
}
