package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话标题清洗：模型摘要 ≤10 字、去标点；首问截断工具不作为产品主路径。
 */
class SessionTitleTextsTest {

    @Test
    void cleanKeepsAtMostTenCharsAndStripsPunctuation() {
        assertEquals("天气出行建议", SessionTitleTexts.clean("标题：天气、出行建议。\n这是解释"));
        assertEquals("短标题", SessionTitleTexts.clean("短标题"));
        assertEquals("一二三四五六七八九十",
                SessionTitleTexts.clean("一二三四五六七八九十十一"));
        assertEquals("", SessionTitleTexts.clean("   "));
        assertEquals("", SessionTitleTexts.clean(null));
    }

    @Test
    void fromFirstMessageIsToolOnlyAndKeepsPunctuation() {
        String longAsk = "请问明天北京会不会下雨，我要不要带伞出门？还有地铁会不会停运";
        String clipped = SessionTitleTexts.fromFirstMessage(longAsk);
        assertTrue(clipped.endsWith(SessionTitleTexts.ELLIPSIS));
        assertEquals(SessionTitleTexts.FIRST_MESSAGE_LIMIT + SessionTitleTexts.ELLIPSIS.length(),
                clipped.length());
        assertEquals("你好，世界", SessionTitleTexts.fromFirstMessage("你好，世界"));
    }
}
