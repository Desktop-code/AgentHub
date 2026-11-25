package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户附图清单：摘要往返、脱敏不伤地址、记忆窗口不把清单当原话。
 */
class UserMessageImagesTest {

    @Test
    void manifestRoundTripKeepsOrder() {
        String first = "/uploads/trial-images/7/aaa.png";
        String second = "/uploads/trial-images/7/bbb.png";
        String manifest = UserMessageImages.manifest(List.of(first, second));
        assertEquals(List.of(first, second), UserMessageImages.imagesOf(manifest));
        // 摘要列还会再走一遍脱敏截断，地址必须原样留下来
        assertEquals(manifest, TrialTexts.summary(manifest));
    }

    @Test
    void plainTextIsNotTreatedAsImages() {
        assertNull(UserMessageImages.manifest(List.of()));
        assertTrue(UserMessageImages.imagesOf("你好，看下这段路径 C:\\tmp\\a.png").isEmpty());
        assertEquals("你好", UserMessageImages.questionText("你好", "你好"));
        assertEquals("只有摘要", UserMessageImages.questionText(null, "只有摘要"));
    }

    @Test
    void imageOnlyDoesNotLeakManifestIntoQuestion() {
        String manifest = UserMessageImages.manifest(List.of("/uploads/trial-images/3/c.png"));
        assertEquals("", UserMessageImages.questionText(null, manifest));
        assertEquals("图旁的字", UserMessageImages.questionText("图旁的字", manifest));
    }
}
