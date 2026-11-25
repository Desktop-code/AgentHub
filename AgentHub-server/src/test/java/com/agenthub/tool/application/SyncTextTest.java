package com.agenthub.tool.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步摘要里不能留下鉴权头或 Bearer 值。
 */
class SyncTextTest {

    /**
     * 整段头、冒号后的值、以及报文里的 Bearer 都要换成占位。
     */
    @Test
    void stripsHeaderAndBearer() {
        String secret = "Authorization: Bearer secret-token";
        String text = SyncText.safe("HTTP 401 " + secret + " bearer secret-token", secret);
        assertFalse(text.contains("secret-token"));
        assertTrue(text.contains("***"));
    }
}
