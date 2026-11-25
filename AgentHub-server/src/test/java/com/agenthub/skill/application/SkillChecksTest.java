package com.agenthub.skill.application;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启停和正文长度。正文上限是应用层字符数，不是把 MEDIUMTEXT 写满。
 * 版本号不再由入参决定（P1 起只能由发布 / 设为生效产生），故不再测默认版本。
 */
class SkillChecksTest {

    @Test
    void statusRejectsOtherValues() {
        assertEquals(1, SkillChecks.initialStatus(null));
        assertEquals(0, SkillChecks.exactStatus(0));
        assertNull(SkillChecks.optionalStatus(null));
        assertThrows(BizException.class, () -> SkillChecks.exactStatus(null));
        assertThrows(BizException.class, () -> SkillChecks.exactStatus(2));
    }

    @Test
    void contentAllowsEmptyAndRejectsOverLimit() {
        assertNull(SkillChecks.content("  "));
        assertEquals("# hi", SkillChecks.content("# hi"));
        String tooLong = "a".repeat(SkillChecks.CONTENT_MAX + 1);
        BizException ex = assertThrows(BizException.class, () -> SkillChecks.content(tooLong));
        assertTrue(ex.getMessage().contains("正文"));
    }
}
