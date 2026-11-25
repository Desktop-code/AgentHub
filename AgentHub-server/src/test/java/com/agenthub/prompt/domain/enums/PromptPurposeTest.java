package com.agenthub.prompt.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途枚举口径。所属域：prompt 的 domain 单测。
 * <p>
 * 锁死：可配提示词模板的用途只有 CHAT / ROUTER / REWRITE / SUMMARY / MEMORY；
 * 长期记忆说明是平台内置锁定文案，<b>不是</b> PromptPurpose，禁止重新加回 MEMORY_RUNTIME。
 */
class PromptPurposeTest {

    /**
     * 枚举与控制台下拉同源；多一个值就会在提示词中心用途下拉里出现。
     */
    @Test
    void canonicalPurposesExcludeMemoryRuntime() {
        assertEquals(List.of(PromptPurpose.CHAT, PromptPurpose.ROUTER, PromptPurpose.REWRITE,
                        PromptPurpose.SUMMARY, PromptPurpose.MEMORY),
                PromptPurpose.allCanonical());
        assertFalse(PromptPurpose.allCanonical().stream()
                .anyMatch(item -> "MEMORY_RUNTIME".equals(item.name())));
    }

    /**
     * 旧值 MEMORY_RUNTIME 必须解析失败，避免历史配置悄悄复活一个不存在的用途。
     */
    @Test
    void memoryRuntimeIsRejected() {
        assertTrue(PromptPurpose.tryParse("MEMORY_RUNTIME").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PromptPurpose.parse("MEMORY_RUNTIME"));
    }
}
