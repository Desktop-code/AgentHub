package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.MemoryItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量存在性判定的唯一口径（刀8）。写侧、联删侧、补同步侧共用，口径漂移会直接造成
 * 「MySQL 说没有、向量库里还留着点」。
 */
class MemoryVectorPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 0);

    @Test
    void validLongItemShouldHaveVector() {
        assertTrue(MemoryVectorPolicy.shouldHaveVector(item("LONG", 1, NOW.plusDays(1), "用户住广州"), NOW));
    }

    @Test
    void nonLongNeverHasVector() {
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("SHORT", 1, null, "本轮上下文"), NOW));
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("AGENT", 1, null, "Agent 级知识"), NOW));
    }

    @Test
    void disabledItemShouldNotHaveVector() {
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("LONG", 0, null, "用户住广州"), NOW));
    }

    @Test
    void expiredItemShouldNotHaveVector() {
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("LONG", 1, NOW.minusMinutes(1), "用户住广州"), NOW));
    }

    @Test
    void exactlyNowCountsAsExpired() {
        // 边界取「不晚于当前」：恰好到点即过期，与 SQL 的 <= 口径一致
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("LONG", 1, NOW, "用户住广州"), NOW));
    }

    @Test
    void blankContentShouldNotHaveVector() {
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("LONG", 1, null, "   "), NOW));
        assertFalse(MemoryVectorPolicy.shouldHaveVector(item("LONG", 1, null, null), NOW));
    }

    @Test
    void nullItemShouldNotHaveVector() {
        assertFalse(MemoryVectorPolicy.shouldHaveVector(null, NOW));
        MemoryItem unsaved = item("LONG", 1, null, "用户住广州");
        unsaved.setId(null);
        assertFalse(MemoryVectorPolicy.shouldHaveVector(unsaved, NOW));
    }

    private static MemoryItem item(String scope, int status, LocalDateTime expired, String content) {
        MemoryItem item = new MemoryItem();
        item.setId(1L);
        item.setAgentId(1L);
        item.setUserId(2L);
        item.setScope(scope);
        item.setStatus(status);
        item.setExpiredTime(expired);
        item.setContent(content);
        return item;
    }
}
