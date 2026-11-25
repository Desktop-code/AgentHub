package com.agenthub.prompt.application;

import com.agenthub.common.error.BizException;
import com.agenthub.prompt.api.dto.PromptSegmentReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途 / locale / templateKey 收口。所属域：prompt。
 */
class PromptChecksTest {

    @Test
    void localeDefaultsToZhCn() {
        assertEquals("zh-CN", PromptChecks.locale(null));
        assertEquals("zh-CN", PromptChecks.locale("  "));
        assertEquals("en-US", PromptChecks.locale(" en-US "));
    }

    @Test
    void purposeRejectsUnknown() {
        assertEquals("CHAT", PromptChecks.purpose("chat"));
        BizException ex = assertThrows(BizException.class, () -> PromptChecks.purpose("INTENT"));
        assertTrue(ex.getMessage().contains("用途"));
    }

    @Test
    void templateKeyNormalized() {
        assertEquals("CHAT_DEFAULT", PromptChecks.templateKey("chat_default"));
        assertThrows(BizException.class, () -> PromptChecks.templateKey("1BAD"));
    }

    @Test
    void requireDraftRejectsPublished() {
        assertThrows(BizException.class, () -> PromptChecks.requireDraft(1));
        assertThrows(BizException.class, () -> PromptChecks.requireDraftForPublish(1));
        PromptChecks.requireDraft(0);
    }

    @Test
    void assertSegmentsDetectsDup() {
        PromptSegmentReq a = new PromptSegmentReq();
        a.setSegKey("role");
        PromptSegmentReq b = new PromptSegmentReq();
        b.setSegKey(" role ");
        BizException ex = assertThrows(BizException.class,
                () -> PromptChecks.assertSegments(List.of(a, b), PromptSegmentReq::getSegKey));
        assertTrue(ex.getMessage().contains("重复"));
    }
}
