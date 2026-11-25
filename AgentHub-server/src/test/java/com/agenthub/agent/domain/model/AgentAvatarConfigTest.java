package com.agenthub.agent.domain.model;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 形象配置（avatar_config JSON）。所属域：agent（单测）。
 */
class AgentAvatarConfigTest {

    @Test
    void of_allBlankBecomesNull() {
        assertNull(AgentAvatarConfig.of(null, null, null, null));
        assertNull(AgentAvatarConfig.of("", " ", "", "  "));
    }

    @Test
    void of_presetNormalizesIconAndColor() {
        AgentAvatarConfig config = AgentAvatarConfig.of("preset", "LOOK-GUIDE", "#635bff", null);
        assertNotNull(config);
        assertEquals(AgentAvatarConfig.TYPE_PRESET, config.getType());
        assertEquals("look-guide", config.getIcon());
        assertEquals("#635BFF", config.getColor());
        assertNull(config.getUrl());
    }

    @Test
    void of_urlOnlyImpliesCustom() {
        AgentAvatarConfig config = AgentAvatarConfig.of(null, null, null, "/uploads/agent-avatars/7/a1b2.png");
        assertNotNull(config);
        assertEquals(AgentAvatarConfig.TYPE_CUSTOM, config.getType());
        assertTrue(config.custom());
        assertEquals("/uploads/agent-avatars/7/a1b2.png", config.getUrl());
        // custom 不保留预置图标与色板
        assertNull(config.getIcon());
        assertNull(config.getColor());
    }

    @Test
    void of_rejectsExternalUrl() {
        assertThrows(BizException.class,
                () -> AgentAvatarConfig.of("custom", null, null, "https://evil.example.com/a.png"));
        assertThrows(BizException.class,
                () -> AgentAvatarConfig.of("custom", null, null, "/uploads/agent-avatars/../secret.png"));
    }

    @Test
    void of_rejectsUnknownIconOrColor() {
        assertThrows(BizException.class, () -> AgentAvatarConfig.of("preset", "demo-bot", null, null));
        assertThrows(BizException.class, () -> AgentAvatarConfig.of("preset", null, "#123456", null));
    }

    @Test
    void jsonRoundTrip() {
        AgentAvatarConfig preset = AgentAvatarConfig.of("preset", "look-scholar", "#8B5CF6", null);
        AgentAvatarConfig parsed = AgentAvatarConfig.fromJson(preset.toJson());
        assertNotNull(parsed);
        assertEquals("look-scholar", parsed.getIcon());
        assertEquals("#8B5CF6", parsed.getColor());
    }

    @Test
    void fromJson_blankOrDirtyBecomesNull() {
        assertNull(AgentAvatarConfig.fromJson(null));
        assertNull(AgentAvatarConfig.fromJson("  "));
        assertNull(AgentAvatarConfig.fromJson("not-json"));
        assertNull(AgentAvatarConfig.fromJson("{}"));
    }
}
