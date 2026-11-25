package com.agenthub.obs.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文只打码，不因为变长被截断。
 */
class ObsTextsTest {

    @Test
    void maskReplacesKeyAndKeepsTheRest() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            body.append('Q');
        }
        body.append(" sk-FixtureKey123456 tail");
        String masked = ObsTexts.mask(body.toString());
        assertTrue(masked.length() > 2000);
        assertTrue(masked.contains("sk-***"));
        assertTrue(masked.endsWith(" tail"));
        assertFalse(masked.contains("FixtureKey"));
        assertFalse(masked.contains("已截断"));
    }

    @Test
    void blankBecomesNull() {
        assertNull(ObsTexts.mask("   "));
        assertNull(ObsTexts.mask(null));
    }
}
