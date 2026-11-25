package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件正文怎么交给模型：有字的 PDF 页不进视觉，没字的页才认；全文不替代用户原话。
 */
class AttachmentTextsTest {

    @Test
    void textPageStaysLocalAndSparsePageUsesVision() {
        List<Integer> called = new ArrayList<>();
        AttachmentTexts.PdfRead read = AttachmentTexts.readPdf("mix.pdf",
                List.of("Qingning passphrase 7741", " \n"),
                vision(true, called, "页图上的句子"),
                new int[1]);
        assertEquals(List.of(2), called);
        assertTrue(read.section().contains("Qingning passphrase 7741"));
        assertTrue(read.section().contains("页图上的句子"));
        assertTrue(read.notices().isEmpty());
        assertFalse(read.section().contains("［文件］"));
    }

    @Test
    void sparsePageWithoutVisionIsExplainedAndTextPageRemains() {
        AttachmentTexts.PdfRead read = AttachmentTexts.readPdf("mix.pdf",
                List.of("这一页有足够的可复制文字", ""),
                vision(false, new ArrayList<>(), "不该出现"),
                new int[1]);
        assertTrue(read.section().contains("这一页有足够的可复制文字"));
        assertFalse(read.section().contains("不该出现"));
        assertEquals(1, read.notices().size());
        assertTrue(read.notices().get(0).contains("第 2 页是图片，当前未绑定视觉模型，无法识别"));
    }

    @Test
    void visionFailureIsVisibleAndDoesNotInventText() {
        AttachmentTexts.PdfRead read = AttachmentTexts.readPdf("scan.pdf",
                List.of(""),
                vision(true, new ArrayList<>(), "  "),
                new int[1]);
        assertTrue(read.section().contains("本页图片识别失败，无法读取"));
        assertTrue(read.notices().get(0).contains("图片识别失败"));
        assertFalse(read.section().contains("编造"));
    }

    @Test
    void fileOnlyPromptCarriesBodyNotPlaceholder() {
        AttachmentTexts.Composed composed = AttachmentTexts.compose("",
                List.of(AttachmentTexts.section("note.txt", "独特句子青柠密语7741")));
        assertTrue(composed.text().startsWith(AttachmentTexts.HEADER));
        assertTrue(composed.text().contains("独特句子青柠密语7741"));
        assertFalse(composed.text().contains("［文件］"));
        assertFalse(composed.truncated());
    }

    @Test
    void longBodyIsClippedWithAVisibleMarkAndKeepsTheOpening() {
        String body = "独特句子青柠密语7741" + "a".repeat(TrialTexts.CONTENT_LIMIT);
        AttachmentTexts.Composed composed = AttachmentTexts.compose("这个文件说了什么", List.of(body));
        assertTrue(composed.truncated());
        assertTrue(composed.text().contains("这个文件说了什么"));
        assertTrue(composed.text().contains("独特句子青柠密语7741"));
        assertTrue(composed.text().contains(AttachmentTexts.TRUNCATED));
    }

    @Test
    void visionPageBudgetIsSharedAcrossCalls() {
        int[] used = new int[1];
        used[0] = AttachmentTexts.MAX_VISION_PAGES;
        List<Integer> called = new ArrayList<>();
        AttachmentTexts.PdfRead read = AttachmentTexts.readPdf("more.pdf",
                List.of(""),
                vision(true, called, "不应调用"),
                used);
        assertTrue(called.isEmpty());
        assertTrue(read.notices().get(0).contains("已达上限"));
    }

    private static AttachmentTexts.PageVision vision(boolean available, List<Integer> called, String text) {
        return new AttachmentTexts.PageVision() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public String recognize(int pageNumber) {
                if (!available) {
                    throw new AssertionError("有字或未绑定视觉时不应识别");
                }
                called.add(pageNumber);
                return text;
            }
        };
    }
}
