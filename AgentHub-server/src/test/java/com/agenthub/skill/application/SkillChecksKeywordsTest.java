package com.agenthub.skill.application;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发关键词入参收口：Tag 数组 → 库内逗号串，并拦住个数与长度。所属域：skill 的单测。
 */
class SkillChecksKeywordsTest {

    @Test
    void keywordsJoinTagsIntoCommaString() {
        assertEquals("脱敏,摘要", SkillChecks.keywords(Arrays.asList("脱敏", " 摘要 ")));
        // 空项被丢弃；全空时存 NULL
        assertEquals("脱敏", SkillChecks.keywords(Arrays.asList("脱敏", "", "  ")));
        assertNull(SkillChecks.keywords(null));
        assertNull(SkillChecks.keywords(List.of()));
    }

    @Test
    void keywordsRejectTooManyItems() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            many.add("k" + i);
        }
        BizException ex = assertThrows(BizException.class, () -> SkillChecks.keywords(many));
        assertTrue(ex.getMessage().contains("最多"));
    }

    @Test
    void keywordsRejectOverlongItem() {
        String tooLong = "a".repeat(33);
        BizException ex = assertThrows(BizException.class, () -> SkillChecks.keywords(List.of(tooLong)));
        assertTrue(ex.getMessage().contains("最长"));
    }

    @Test
    void keywordsRejectOverColumnWidth() {
        // 单项不超长，但把逗号串撑过 255 列宽：关键词必须各不相同，否则会先被去重
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("k" + String.format("%02d", i) + "a".repeat(13));
        }
        BizException ex = assertThrows(BizException.class, () -> SkillChecks.keywords(many));
        assertTrue(ex.getMessage().contains("合计最长"));
    }
}
