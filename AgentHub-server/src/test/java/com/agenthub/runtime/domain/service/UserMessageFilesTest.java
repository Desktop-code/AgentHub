package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非图片附件清单：类型闸门、摘要往返，以及不能把清单当成用户原话。
 */
class UserMessageFilesTest {

    @Test
    void manifestKeepsAddOrderAndSurvivesSummaryClip() {
        String image = "/uploads/trial-images/7/aaa.png";
        UserMessageFiles.Ref file = new UserMessageFiles.Ref("季度,报表.pdf", 2048, "/uploads/trial-files/7/bbb.pdf");
        String manifest = UserMessageFiles.manifest(List.of("image", "file"), List.of(image), List.of(file));
        assertTrue(manifest.startsWith("ahfile:2:"));
        assertEquals(manifest, TrialTexts.summary(manifest));
        List<UserMessageFiles.Item> items = UserMessageFiles.itemsOf(manifest);
        assertEquals("image", items.get(0).kind());
        assertEquals(image, items.get(0).url());
        assertEquals("file", items.get(1).kind());
        assertEquals("季度,报表.pdf", items.get(1).name());
        assertEquals(2048, items.get(1).size());
        String percent = UserMessageFiles.manifest(
                List.of("file"),
                List.of(),
                List.of(new UserMessageFiles.Ref("报价%7C表.pdf", 12, "/uploads/trial-files/7/c.pdf")));
        assertEquals("报价%7C表.pdf", UserMessageFiles.itemsOf(percent).get(0).name());
        // 纯图片清单不被这条解析器认走，已有 ahimg 回看保持原样
        assertTrue(UserMessageImages.imagesOf(manifest).isEmpty());
        assertEquals("", UserMessageImages.questionText(null, manifest));
        assertEquals("看一下", UserMessageImages.questionText("看一下", manifest));
    }

    @Test
    void rejectsExecutableAndImageDisguisedAsFile() {
        TrialFailure exe = assertThrows(TrialFailure.class, () -> UserMessageFiles.normalize(List.of(
                new UserMessageFiles.NamedPayload("setup.exe", "data:application/octet-stream;base64,YQ=="))));
        assertTrue(exe.getMessage().contains("可执行文件"));
        TrialFailure image = assertThrows(TrialFailure.class, () -> UserMessageFiles.normalize(List.of(
                new UserMessageFiles.NamedPayload("a.png", "data:image/png;base64,YQ=="))));
        assertTrue(image.getMessage().contains("图片"));
        List<UserMessageFiles.Draft> pdf = UserMessageFiles.normalize(List.of(
                new UserMessageFiles.NamedPayload("C:\\tmp\\a.pdf", "data:application/pdf;base64,YQ==")));
        assertEquals("a.pdf", pdf.get(0).name());
        assertEquals("pdf", pdf.get(0).extension());
    }

    @Test
    void tenFilesRejectedAndNineRoundTrip() {
        List<UserMessageFiles.NamedPayload> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new UserMessageFiles.NamedPayload("a" + i + ".pdf", "data:application/pdf;base64,YQ=="));
        }
        TrialFailure tooMany = assertThrows(TrialFailure.class, () -> UserMessageFiles.normalize(ten));
        assertTrue(tooMany.getMessage().contains("9"));

        List<UserMessageFiles.Ref> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(new UserMessageFiles.Ref("票" + i + ".pdf", 100 + i, "/uploads/trial-files/7/f" + i + ".pdf"));
        }
        String manifest = UserMessageFiles.manifest(null, List.of(), nine);
        assertEquals(manifest, TrialTexts.summary(manifest));
        List<UserMessageFiles.Item> items = UserMessageFiles.itemsOf(manifest);
        assertEquals(9, items.size());
        assertEquals("票0.pdf", items.get(0).name());
        assertEquals("/uploads/trial-files/7/f0.pdf", items.get(0).url());
        assertEquals("/uploads/trial-files/7/f8.pdf", items.get(8).url());
    }

    @Test
    void rejectsWhenManifestCannotFit() {
        String longName = "报".repeat(76) + ".pdf";
        List<UserMessageFiles.NamedPayload> raw = List.of(
                new UserMessageFiles.NamedPayload(longName, "data:application/pdf;base64,YQ=="),
                new UserMessageFiles.NamedPayload(longName, "data:application/pdf;base64,YQ=="),
                new UserMessageFiles.NamedPayload(longName, "data:application/pdf;base64,YQ=="),
                new UserMessageFiles.NamedPayload(longName, "data:application/pdf;base64,YQ=="));
        List<UserMessageFiles.Draft> drafts = UserMessageFiles.normalize(raw);
        TrialFailure tooLong = assertThrows(TrialFailure.class,
                () -> UserMessageFiles.assertFits(12L, null, 0, drafts));
        assertTrue(tooLong.getMessage().contains("文件名"));
    }
}
