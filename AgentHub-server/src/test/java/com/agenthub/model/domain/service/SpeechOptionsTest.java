package com.agenthub.model.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 语音实例扩展配置：缺省给默认值，坏 JSON 不能拖垮出站，格式必须落在白名单。
 */
class SpeechOptionsTest {

    @Test
    void defaultsWhenMissing() {
        assertEquals(SpeechOptions.DEFAULT_VOICE, SpeechOptions.voiceOf(null));
        assertEquals(SpeechOptions.DEFAULT_VOICE, SpeechOptions.voiceOf("{}"));
        assertEquals(SpeechOptions.DEFAULT_FORMAT, SpeechOptions.formatOf("not-json"));
    }

    @Test
    void readsConfiguredVoiceAndFormat() {
        assertEquals("nova", SpeechOptions.voiceOf("{\"voice\":\" nova \"}"));
        assertEquals("wav", SpeechOptions.formatOf("{\"format\":\"WAV\"}"));
    }

    @Test
    void rejectsFormatOutsideWhitelist() {
        assertEquals(SpeechOptions.DEFAULT_FORMAT, SpeechOptions.formatOf("{\"format\":\"exe\"}"));
    }

    @Test
    void ignoresNonTextValues() {
        assertEquals(SpeechOptions.DEFAULT_VOICE, SpeechOptions.voiceOf("{\"voice\":123}"));
    }
}
