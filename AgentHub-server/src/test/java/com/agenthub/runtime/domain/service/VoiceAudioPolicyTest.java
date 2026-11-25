package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录音闸门：格式只认文件头，超限与空录都要有明确中文文案。
 */
class VoiceAudioPolicyTest {

    private static final int MAX = 10 * 1024 * 1024;

    @Test
    void detectsCommonBrowserFormats() {
        assertEquals("webm", VoiceAudioPolicy.detect(withPrefix(new byte[]{(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})).extension());
        assertEquals("wav", VoiceAudioPolicy.detect(withPrefix("RIFF".getBytes(StandardCharsets.US_ASCII), 4,
                "WAVE".getBytes(StandardCharsets.US_ASCII))).extension());
        assertEquals("ogg", VoiceAudioPolicy.detect(withPrefix("OggS".getBytes(StandardCharsets.US_ASCII))).extension());
        assertEquals("m4a", VoiceAudioPolicy.detect(withPrefix(new byte[]{0, 0, 0, 0}, 0,
                "ftyp".getBytes(StandardCharsets.US_ASCII))).extension());
        assertEquals("mp3", VoiceAudioPolicy.detect(withPrefix("ID3".getBytes(StandardCharsets.US_ASCII))).extension());
    }

    @Test
    void rejectsUnknownHeader() {
        assertNull(VoiceAudioPolicy.detect("not-audio".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void rejectsOverLimitWithChineseMessage() {
        byte[] huge = new byte[MAX + 1];
        huge[0] = (byte) 0x1A;
        huge[1] = 0x45;
        huge[2] = (byte) 0xDF;
        huge[3] = (byte) 0xA3;
        BizException ex = assertThrows(BizException.class, () -> VoiceAudioPolicy.require(huge, MAX));
        assertTrue(ex.getMessage().contains("10MB"));
    }

    @Test
    void rejectsEmptyAndTooShort() {
        BizException empty = assertThrows(BizException.class, () -> VoiceAudioPolicy.require(new byte[0], MAX));
        assertTrue(empty.getMessage().contains("没有收到录音"));
        BizException shortOne = assertThrows(BizException.class, () -> VoiceAudioPolicy.require(new byte[16], MAX));
        assertTrue(shortOne.getMessage().contains("录音太短"));
    }

    @Test
    void rejectsUnsupportedFormat() {
        byte[] unknown = new byte[1024];
        Arrays.fill(unknown, (byte) 0x7F);
        BizException ex = assertThrows(BizException.class, () -> VoiceAudioPolicy.require(unknown, MAX));
        assertTrue(ex.getMessage().contains("音频格式不支持"));
    }

    private static byte[] withPrefix(byte[] prefix) {
        return withPrefix(prefix, 0, new byte[0]);
    }

    private static byte[] withPrefix(byte[] head, int gap, byte[] tail) {
        byte[] out = new byte[Math.max(64, head.length + gap + tail.length)];
        System.arraycopy(head, 0, out, 0, head.length);
        if (tail.length > 0) {
            System.arraycopy(tail, 0, out, head.length + gap, tail.length);
        }
        return out;
    }
}
