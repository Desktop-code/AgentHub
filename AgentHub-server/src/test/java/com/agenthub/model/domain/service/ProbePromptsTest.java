package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本生成族探活文案：共用聊天端点，但提示词必须保持原用途，不能一律写成 CHAT 的 ping。
 * 本文件只在本机执行（src/test 不入库）。
 */
class ProbePromptsTest {

    @Test
    void chatUsesMinimalPing() {
        assertEquals("ping", ProbePrompts.chat(ModelCapability.CHAT));
        assertEquals("ping", ProbePrompts.chat(null));
    }

    @Test
    void nonChatGenerationPurposesKeepTheirOwnText() {
        String router = ProbePrompts.chat(ModelCapability.ROUTER);
        String rewrite = ProbePrompts.chat(ModelCapability.REWRITE);
        String summary = ProbePrompts.chat(ModelCapability.SUMMARY);
        String memory = ProbePrompts.chat(ModelCapability.MEMORY);

        assertTrue(router.contains("路由"), router);
        assertTrue(rewrite.contains("改写"), rewrite);
        assertTrue(summary.contains("概括"), summary);
        assertTrue(memory.contains("记住"), memory);
        // 四种用途互不复用 CHAT 的 ping，避免日志把人误导成「都在测 CHAT」
        assertNotEquals("ping", router);
        assertNotEquals("ping", rewrite);
        assertNotEquals("ping", summary);
        assertNotEquals("ping", memory);
    }
}
