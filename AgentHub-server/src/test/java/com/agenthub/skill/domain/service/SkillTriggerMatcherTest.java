package com.agenthub.skill.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发关键词口径：逗号串 ↔ Tag 数组、命中判断。所属域：skill 的单测。
 * 覆盖全角逗号、空项、大小写去重，以及「未命中不得误触发」这条最要紧的边界。
 */
class SkillTriggerMatcherTest {

    @Test
    void splitHandlesBlankFullWidthCommaAndDuplicates() {
        // 库内可能被手工写成全角逗号或夹着空项，都要拆干净
        assertEquals(List.of("脱敏", "摘要"), SkillTriggerMatcher.split("脱敏, 摘要"));
        assertEquals(List.of("脱敏", "摘要"), SkillTriggerMatcher.split("脱敏，摘要"));
        assertEquals(List.of("脱敏"), SkillTriggerMatcher.split(" ,脱敏,,  ,"));
        // 大小写不同视为同一个词，保留首次写法
        assertEquals(List.of("OCR"), SkillTriggerMatcher.split("OCR,ocr"));
        assertTrue(SkillTriggerMatcher.split(null).isEmpty());
        assertTrue(SkillTriggerMatcher.split("   ").isEmpty());
    }

    @Test
    void joinDropsBlankItemsAndReturnsNullWhenEmpty() {
        assertEquals("脱敏,摘要", SkillTriggerMatcher.join(Arrays.asList(" 脱敏 ", "摘要")));
        assertEquals("脱敏", SkillTriggerMatcher.join(Arrays.asList("脱敏", "脱敏", " ")));
        // 没有有效关键词时存 NULL，不存空串
        assertNull(SkillTriggerMatcher.join(null));
        assertNull(SkillTriggerMatcher.join(List.of()));
        assertNull(SkillTriggerMatcher.join(Arrays.asList("", "  ")));
    }

    @Test
    void matchesAnyKeywordInsideUserQuery() {
        assertTrue(SkillTriggerMatcher.matches("帮我把这段数据脱敏后再发", "脱敏,摘要"));
        assertTrue(SkillTriggerMatcher.matches("请生成摘要", "脱敏,摘要"));
        // 大小写不敏感：英文关键词命中
        assertTrue(SkillTriggerMatcher.matches("use ocr here", "OCR"));
    }

    @Test
    void doesNotMatchWhenKeywordAbsentOrInputBlank() {
        // 未命中不得硬触发，否则无关正文会被塞进上下文
        assertFalse(SkillTriggerMatcher.matches("今天天气如何", "脱敏,摘要"));
        assertFalse(SkillTriggerMatcher.matches("今天天气如何", null));
        assertFalse(SkillTriggerMatcher.matches("今天天气如何", "  "));
        assertFalse(SkillTriggerMatcher.matches(null, "脱敏"));
        assertFalse(SkillTriggerMatcher.matches("   ", "脱敏"));
    }
}
