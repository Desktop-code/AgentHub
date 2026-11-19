package com.agenthub.runtime.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 把已抽出的附件正文拼进送给对话模型的用户话。所属域：runtime。
 *
 * <p>气泡只展示文件名和用户原话，这里的全文只出现在模型上下文里。
 * PDF 按页决定：已经有可复制文字的页直接用字，几乎没字的页才交给调用方去认。
 * 本类不读盘、不调用模型。
 */
public final class AttachmentTexts {

    /** 模型侧段落标题。和图片 OCR 的「图片内容提取结果」分开，避免把文档当成另一张图 */
    public static final String HEADER = "【附件正文】";

    /** 单页去掉空白后少于此值，视为几乎没有可复制文字 */
    public static final int MIN_PAGE_CHARS = 8;

    /** 一轮里最多拿去视觉模型的图片页，与知识库扫描页上限同一量级 */
    public static final int MAX_VISION_PAGES = 15;

    /** 压缩包不解开。模型侧和用户侧用同一句，避免一边说读过、一边说没有 */
    public static final String ZIP_NOTE = "目前不能读取压缩包内的文件";

    /** 抽不出正文时的短说明 */
    public static final String UNREADABLE = "无法读取该文件";

    /** 截断标记。留在正文末尾，模型知道后面还有内容没给到 */
    public static final String TRUNCATED = "（正文过长，已截断，以上为前段）";

    private AttachmentTexts() {
    }

    /**
     * 这一页是否已经有足够的可复制文字。
     *
     * @param pageText 文字层，可空
     * @return true 表示直接用抽出的字，不必再送视觉模型
     */
    public static boolean pageHasText(String pageText) {
        return compact(pageText).length() >= MIN_PAGE_CHARS;
    }

    /**
     * 拼一轮送给模型的用户话：原话在前，附件正文在后。没有附件时原样返回原话。
     *
     * @param spoken   用户打的字，可空。只发文件时为空
     * @param sections 每个文件一段，已含文件名；空段会被丢掉
     * @return 模型文本，以及附件块是否被截断
     */
    public static Composed compose(String spoken, List<String> sections) {
        StringBuilder body = new StringBuilder();
        if (sections != null) {
            for (String section : sections) {
                if (section == null || section.isBlank()) {
                    continue;
                }
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append(section.trim());
            }
        }
        String question = spoken == null ? "" : spoken.trim();
        if (body.length() == 0) {
            return new Composed(question, false);
        }
        String block = HEADER + "\n" + body;
        boolean truncated = false;
        if (block.length() > TrialTexts.CONTENT_LIMIT) {
            truncated = true;
            int room = TrialTexts.CONTENT_LIMIT - TRUNCATED.length() - 1;
            if (room < 1) {
                block = TRUNCATED;
            } else {
                block = block.substring(0, room) + "\n" + TRUNCATED;
            }
        }
        if (question.isEmpty()) {
            return new Composed(block, truncated);
        }
        return new Composed(question + "\n\n" + block, truncated);
    }

    /**
     * 单个文件在模型上下文里的一段。
     *
     * @param fileName 展示名
     * @param body     正文或失败说明
     * @return 带文件名的一段；过长时在本段内截断并注明
     */
    public static String section(String fileName, String body) {
        String name = fileName == null || fileName.isBlank() ? "未命名文件" : oneLine(fileName);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            text = UNREADABLE;
        }
        String raw = "文件「" + name + "」：\n" + text;
        if (raw.length() <= TrialTexts.CONTENT_LIMIT) {
            return raw;
        }
        int room = TrialTexts.CONTENT_LIMIT - TRUNCATED.length() - 1;
        if (room < 1) {
            return TRUNCATED;
        }
        return raw.substring(0, room) + "\n" + TRUNCATED;
    }

    /**
     * 按页组装 PDF。有字的页不调用 {@code vision}。
     *
     * @param fileName   展示名，写进失败说明
     * @param pages      按页文字层，下标 0 是第 1 页
     * @param vision     没字的页才调用；{@code available=false} 时一页都不送
     * @param visionUsed 本轮已经拿去视觉模型的页数，长度至少 1。多份 PDF 共用，避免每份都重新计数
     * @return 这一份 PDF 的模型和用户说明
     */
    public static PdfRead readPdf(String fileName, List<String> pages, PageVision vision, int[] visionUsed) {
        List<String> pageTexts = pages == null ? List.of() : pages;
        if (pageTexts.isEmpty()) {
            String note = (fileName == null ? "未命名文件" : fileName) + "：" + UNREADABLE;
            return new PdfRead(section(fileName, UNREADABLE), List.of(note));
        }
        StringBuilder body = new StringBuilder();
        List<Integer> noVision = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        int used = visionUsed == null || visionUsed.length == 0 ? 0 : Math.max(0, visionUsed[0]);
        boolean visionOn = vision != null && vision.available();
        for (int i = 0; i < pageTexts.size(); i++) {
            int pageNumber = i + 1;
            String extracted = pageTexts.get(i);
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append("第").append(pageNumber).append("页：\n");
            if (pageHasText(extracted)) {
                // 这一页已经有可复制文字，禁止再送视觉模型
                body.append(extracted.trim());
                continue;
            }
            if (!visionOn) {
                noVision.add(pageNumber);
                body.append("本页是图片，当前未绑定视觉模型，无法识别");
                continue;
            }
            if (used >= MAX_VISION_PAGES) {
                skipped.add(pageNumber);
                body.append("本页是图片，本轮识别页数已达上限，无法识别");
                continue;
            }
            used++;
            String recognized = vision.recognize(pageNumber);
            if (recognized == null || recognized.isBlank() || VisionOcrTexts.FAILED_MARK.equals(recognized.trim())) {
                failed.add(pageNumber);
                body.append("本页图片识别失败，无法读取");
                continue;
            }
            body.append(recognized.trim());
        }
        if (visionUsed != null && visionUsed.length > 0) {
            visionUsed[0] = used;
        }
        List<String> notices = new ArrayList<>();
        if (!noVision.isEmpty()) {
            notices.add(fileName + "：第 " + joinPages(noVision) + " 页是图片，当前未绑定视觉模型，无法识别");
        }
        if (!failed.isEmpty()) {
            notices.add(fileName + "：第 " + joinPages(failed) + " 页图片识别失败，无法读取");
        }
        if (!skipped.isEmpty()) {
            notices.add(fileName + "：第 " + joinPages(skipped) + " 页是图片，本轮识别页数已达上限，无法识别");
        }
        return new PdfRead(section(fileName, body.toString()), List.copyOf(notices));
    }

    private static String joinPages(List<Integer> pages) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                text.append('、');
            }
            text.append(pages.get(i));
        }
        return text.toString();
    }

    private static String compact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("\\s+", "");
    }

    private static String oneLine(String raw) {
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * 没字的 PDF 页才需要的视觉回调。有字的页不会被调用。
     */
    public interface PageVision {

        /**
         * @return 当前 Agent 是否绑定了视觉模型
         */
        boolean available();

        /**
         * 识别一页。调用方负责渲染成图。
         *
         * @param pageNumber 页码，从 1 开始
         * @return 认出的文字；失败返回空串，不要编造
         */
        String recognize(int pageNumber);
    }

    /**
     * @param text      送给模型的用户话
     * @param truncated 附件块是否因过长被截断
     */
    public record Composed(String text, boolean truncated) {
    }

    /**
     * @param section 这一份 PDF 写入模型上下文的段落
     * @param notices 给用户看的短说明；有字的页不会产生说明
     */
    public record PdfRead(String section, List<String> notices) {

        public PdfRead {
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }
}
