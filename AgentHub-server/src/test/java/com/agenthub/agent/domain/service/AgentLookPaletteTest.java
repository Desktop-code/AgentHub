package com.agenthub.agent.domain.service;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 形象色板白名单。所属域：agent（单测）。
 */
class AgentLookPaletteTest {

    @Test
    void normalizeIcon_blankBecomesNull() {
        assertNull(AgentLookPalette.normalizeIcon(null));
        assertNull(AgentLookPalette.normalizeIcon("  "));
    }

    @Test
    void normalizeIcon_acceptsWhitelist() {
        assertEquals("look-guide", AgentLookPalette.normalizeIcon("look-guide"));
        assertEquals("look-analyst", AgentLookPalette.normalizeIcon("LOOK-ANALYST"));
    }

    @Test
    void normalizeIcon_rejectsUnknown() {
        assertThrows(BizException.class, () -> AgentLookPalette.normalizeIcon("demo-bot"));
    }

    @Test
    void normalizeColor_acceptsPaletteCaseInsensitive() {
        assertEquals("#635BFF", AgentLookPalette.normalizeColor("#635bff"));
        assertEquals("#3B82F6", AgentLookPalette.normalizeColor("#3b82f6"));
    }

    @Test
    void normalizeColor_rejectsArbitrary() {
        assertThrows(BizException.class, () -> AgentLookPalette.normalizeColor("#123456"));
    }

    @Test
    void paletteContainsThemePurple() {
        assertTrue(AgentLookPalette.COLORS.contains(AgentLookPalette.DEFAULT_COLOR));
        assertTrue(AgentLookPalette.ICONS.contains("look-guide"));
    }
}
