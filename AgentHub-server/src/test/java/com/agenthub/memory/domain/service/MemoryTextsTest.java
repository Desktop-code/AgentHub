package com.agenthub.memory.domain.service;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式记住、密钥拦截和抽取 JSON（含模型常见包壳）。
 */
class MemoryTextsTest {

    @Test
    void rememberPhraseAndSecret() {
        assertTrue(MemoryTexts.wantsRemember("请记住我住在上海"));
        assertTrue(MemoryTexts.wantsRemember("以后默认用中文"));
        assertTrue(MemoryTexts.wantsRemember("记住它"));
        assertTrue(MemoryTexts.wantsRemember("别忘了我的生日"));
        assertFalse(MemoryTexts.wantsRemember("记住了吗"));
        assertFalse(MemoryTexts.wantsRemember("记不住"));
        assertFalse(MemoryTexts.wantsRemember("今天天气怎么样"));
        assertThrows(BizException.class, () -> MemoryTexts.rejectSecret("api_key=sk-abcdefghij"));
    }

    @Test
    void parsesFencedPreferences() {
        String raw = """
                ```json
                [{"fact_key":"residence_city","title":"城市","content":"默认上海","importance":80}]
                ```
                """;
        List<MemoryTexts.PreferenceDraft> rows = MemoryTexts.parsePreferences(raw);
        assertEquals(1, rows.size());
        assertEquals("residence_city", rows.get(0).getFactKey());
        assertEquals("默认上海", rows.get(0).getContent());
        assertEquals(80, rows.get(0).getImportance());
    }

    @Test
    void parsesWrappedAndAliasedFields() {
        String raw = """
                分析如下：用户有稳定偏好。
                {"preferences":[{"factKey":"occupation","name":"通勤","text":"用户在深圳上班","score":70}]}
                """;
        List<MemoryTexts.PreferenceDraft> rows = MemoryTexts.parsePreferences(raw);
        assertEquals(1, rows.size());
        assertEquals("occupation", rows.get(0).getFactKey());
        assertEquals("通勤", rows.get(0).getTitle());
        assertEquals("用户在深圳上班", rows.get(0).getContent());
        assertEquals(70, rows.get(0).getImportance());
    }

    @Test
    void parsesSingleObject() {
        List<MemoryTexts.PreferenceDraft> rows = MemoryTexts.parsePreferences(
                "{\"title\":\"格式\",\"content\":\"喜欢列表回复\",\"importance\":60}");
        assertEquals(1, rows.size());
        assertEquals("喜欢列表回复", rows.get(0).getContent());
    }

    @Test
    void proseWithoutJsonYieldsEmptyAndNotEmptyPayload() {
        String raw = "根据对话，用户只是在查天气，没有可长期成立的偏好，因此不写入记忆。";
        assertTrue(MemoryTexts.parsePreferences(raw).isEmpty());
        assertFalse(MemoryTexts.isEmptyExtractPayload(raw));
        assertTrue(MemoryTexts.isEmptyExtractPayload("[]"));
        assertTrue(MemoryTexts.isEmptyExtractPayload("```json\n[]\n```"));
    }
}
