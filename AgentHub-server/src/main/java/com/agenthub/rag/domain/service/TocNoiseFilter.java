package com.agenthub.rag.domain.service;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 目录 / 目次 / 电子书翻页导航噪声过滤。
 * <p>
 * PDF、Word、TXT 解析后常见噪声：
 * <ul>
 *   <li>经典目录：标题 + 点线/空格/全角空格 + 页码</li>
 *   <li>电子书导航：上一页 / 当前页 / 下一页 / 返回目录</li>
 *   <li>导出重复短标题行</li>
 * </ul>
 * 策略：切分前剥离目录区与导航行；切分后再丢掉目录型低质量切片。
 */
public final class TocNoiseFilter {

    /** 章节标题是否目录类（目录、Contents、TOC 等）。 */
    private static final Pattern TOC_TITLE = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:目\\s*录|目\\s*次|contents?|table\\s+of\\s+contents|toc|索引)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 典型目录行：标题 + 点线/空格 + 页码。 */
    private static final Pattern TOC_LINE = Pattern.compile(
            "^.{2,80}?(?:\\.{2,}|…{1,}|·{2,}|\\s{2,})\\s*\\d{1,4}\\s*$");

    /** 短标题尾随页码，如「第一章 总则  12」「1.2 退货政策 5」。 */
    private static final Pattern TOC_LINE_PAGE_TAIL = Pattern.compile(
            "^(?:第[一二三四五六七八九十百零〇0-9]+[章节篇部]|\\d+(?:\\.\\d+)*)\\s*.{1,40}?\\s+\\d{1,4}\\s*$");

    /**
     * 中文电子书目录常见：短标题 + 全角/半角空白 + 页码，无点线。
     * 例：「感冒　　12」「观发识病　3」
     */
    private static final Pattern TOC_LINE_IDEO_SPACE = Pattern.compile(
            "^[\\u4e00-\\u9fffA-Za-z0-9（）()《》·、\\-—]{2,30}[\\s\\u3000\\u00a0]{1,}\\d{1,4}\\s*$");

    /** 电子书翻页导航整行。 */
    private static final Pattern EBOOK_NAV_LINE = Pattern.compile(
            "^[\\s\\u3000]*(?:上一页|当前页|下一页|返回目录|回目录|目录页|返回书目)[:：].*$");

    /** 行内夹杂的导航片段（解析后偶发粘在段尾）。 */
    private static final Pattern EBOOK_NAV_INLINE = Pattern.compile(
            "(?:^|[\\s\\u3000]+)(?:上一页|当前页|下一页|返回目录|回目录)[:：][^\\n]{0,80}");

    /** 茶饮基础知识标题（调制方法等），检索症状时易造成语义漂移。 */
    private static final Pattern TEA_BASE_TITLE = Pattern.compile(
            "茶饮调制|调制方法|冲泡技巧|茶具|水温|基础知识|选茶|存茶|泡茶步骤");

    /** 纯文本目录区最长跳过行数，防止误伤整本无 Markdown 标题的书。 */
    private static final int PLAIN_TOC_MAX_SKIP = 120;

    private TocNoiseFilter() {
    }

    /**
     * 可配领域噪声（历史茶饮调制等）。AgentHub 默认不启用，由 {@code StructureAwareChunker} 开关控制。
     *
     * @param sectionTitle 章节标题
     * @param content      切片正文
     * @return true 表示命中领域噪声规则，应丢弃
     */
    public static boolean isDomainNoise(String sectionTitle, String content) {
        String section = sectionTitle == null ? "" : sectionTitle.trim();
        if (TEA_BASE_TITLE.matcher(section).find()) {
            return true;
        }
        String text = content == null ? "" : content.trim();
        if (text.length() < 80 && TEA_BASE_TITLE.matcher(text).find()) {
            return true;
        }
        return text.startsWith("茶饮调制") || text.startsWith("调制方法");
    }

    /**
     * @deprecated 使用 {@link #isDomainNoise}；保留别名避免旧单测/调用方编译失败
     */
    @Deprecated
    public static boolean isTeaBaseNoise(String sectionTitle, String content) {
        return isDomainNoise(sectionTitle, content);
    }

    /**
     * 切分前清洗：去掉目录章节/目录页、电子书导航行、重复短标题。
     *
     * @param text 已规范化换行的正文
     * @return 清洗后文本，可能为空
     */
    public static String stripBeforeChunk(String text) {
        if (!StringUtils.hasText(text)) {
            return text == null ? "" : text;
        }
        // 必须先按 \\f 分页。Java \\R 含换页符，若先按行拆会把 PDF 页界变成普通换行，目录页会误伤正文页。
        if (text.indexOf('\f') >= 0) {
            String pdf = stripTocPdfPages(text);
            String[] pages = pdf.split("\\f", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages.length; i++) {
                if (i > 0) {
                    sb.append('\f');
                }
                sb.append(cleanPageText(pages[i]));
            }
            return sb.toString();
        }
        return cleanPageText(text);
    }

    /**
     * 单页/整篇：目录节剥离 → 去导航行 → 去重短标题 → 清行内导航残留。
     */
    private static String cleanPageText(String page) {
        String cleaned = stripTocSections(page);
        cleaned = stripEbookNavAndDupTitles(cleaned);
        cleaned = EBOOK_NAV_INLINE.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /**
     * 章节标题是否应整节丢弃。
     */
    public static boolean isTocTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String normalized = title.trim().replaceAll("\\s+", "");
        return TOC_TITLE.matcher(title.trim()).matches()
                || TOC_TITLE.matcher(normalized).matches()
                || "目录".equals(normalized)
                || "目次".equals(normalized)
                || "索引".equals(normalized);
    }

    /**
     * 是否电子书翻页导航行。
     */
    public static boolean isEbookNavLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        return EBOOK_NAV_LINE.matcher(line.trim()).matches();
    }

    /**
     * 整页是否目录页：目录行占比高，或标题后紧跟多行点线页码。
     */
    public static boolean isTocPage(String page) {
        if (!StringUtils.hasText(page)) {
            return false;
        }
        String[] lines = page.split("\\R");
        int meaningful = 0;
        int tocLike = 0;
        boolean sawTocHeading = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            meaningful++;
            if (isTocTitle(line) || isTocTitle(stripLeadingHashes(line))) {
                sawTocHeading = true;
                tocLike++;
                continue;
            }
            if (isEbookNavLine(line) || isTocLine(line)) {
                tocLike++;
            }
        }
        if (meaningful == 0) {
            return false;
        }
        if (sawTocHeading && tocLike >= Math.max(3, (int) Math.ceil(meaningful * 0.5))) {
            return true;
        }
        return meaningful >= 4 && tocLike * 1.0 / meaningful >= 0.6;
    }

    /**
     * 切片是否低质量目录/导航噪声，应丢弃。
     */
    public static boolean isLowQualityChunk(String content) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        String text = content.trim();
        if (text.length() < 12) {
            return !text.matches(".*[。！？].*");
        }
        if (isTocTitle(text) || isTocTitle(stripLeadingHashes(text))) {
            return true;
        }
        String[] lines = text.split("\\R");
        int meaningful = 0;
        int tocLike = 0;
        int navLike = 0;
        int prose = 0;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (isTocTitle(stripLeadingHashes(line))) {
                    tocLike++;
                    meaningful++;
                }
                continue;
            }
            meaningful++;
            if (isEbookNavLine(line)) {
                navLike++;
                tocLike++;
            } else if (isTocLine(line)) {
                tocLike++;
            } else if (looksLikeProse(line)) {
                prose++;
            }
        }
        if (meaningful == 0) {
            return true;
        }
        // 几乎全是翻页导航
        if (navLike >= 2 && navLike * 1.0 / meaningful >= 0.5 && prose == 0) {
            return true;
        }
        if (prose >= 2 && prose >= tocLike) {
            return false;
        }
        return tocLike >= 3 && tocLike * 1.0 / meaningful >= 0.5;
    }

    public static boolean isTocLine(String line) {
        if (!StringUtils.hasText(line) || line.length() < 4) {
            return false;
        }
        String trimmed = line.trim();
        if (isTocTitle(trimmed) || isTocTitle(stripLeadingHashes(trimmed))) {
            return true;
        }
        if (isEbookNavLine(trimmed)) {
            return true;
        }
        if (TOC_LINE.matcher(trimmed).matches()) {
            return true;
        }
        if (TOC_LINE_PAGE_TAIL.matcher(trimmed).matches()) {
            return true;
        }
        return TOC_LINE_IDEO_SPACE.matcher(trimmed).matches();
    }

    /**
     * 去掉「目录」区块。Markdown 按同级标题结束；纯文本按目录行密度 / 正文句读结束。
     */
    static String stripTocSections(String text) {
        String[] lines = text.split("\\R", -1);
        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        int skipLevel = 0;
        boolean plainMode = false;
        int plainSkipped = 0;
        int consecutiveProse = 0;
        for (String line : lines) {
            String heading = extractHeadingTitle(line);
            Integer level = extractHeadingLevel(line);
            if (heading != null && level != null && isTocTitle(heading)) {
                skipping = true;
                skipLevel = level;
                // 纯「目录」行（无 #）→ 纯文本模式，不能一直跳到文件末尾
                plainMode = !line.trim().startsWith("#");
                plainSkipped = 0;
                consecutiveProse = 0;
                continue;
            }
            if (skipping) {
                if (!plainMode && level != null && level <= skipLevel
                        && !isTocTitle(heading == null ? "" : heading)) {
                    skipping = false;
                    kept.add(line);
                    continue;
                }
                String trimmed = line == null ? "" : line.trim();
                if (plainMode) {
                    plainSkipped++;
                    if (plainSkipped > PLAIN_TOC_MAX_SKIP) {
                        skipping = false;
                        kept.add(line);
                        continue;
                    }
                    if (!StringUtils.hasText(trimmed) || isTocLine(trimmed) || isEbookNavLine(trimmed)
                            || looksLikeTocShortTitle(trimmed)) {
                        consecutiveProse = 0;
                        continue;
                    }
                    if (looksLikeProse(trimmed)) {
                        consecutiveProse++;
                        if (consecutiveProse >= 1) {
                            skipping = false;
                            kept.add(line);
                        }
                        continue;
                    }
                    // 未识别行：仍视为目录区一部分，直到出现正文
                    continue;
                }
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).trim();
    }

    /**
     * 对已切好的切片正文再清一遍导航/重复标题（防解析粘连残留）。
     */
    public static String sanitizeChunkText(String content) {
        if (!StringUtils.hasText(content)) {
            return content == null ? "" : content;
        }
        String cleaned = stripEbookNavAndDupTitles(content);
        cleaned = EBOOK_NAV_INLINE.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /**
     * 去掉电子书导航行，并折叠连续重复的短标题（导出常把章节名打两遍）。
     */
    static String stripEbookNavAndDupTitles(String text) {
        if (!StringUtils.hasText(text)) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\\R", -1);
        List<String> kept = new ArrayList<>();
        String lastKeptMeaningful = null;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (isEbookNavLine(trimmed)) {
                continue;
            }
            if (StringUtils.hasText(trimmed) && trimmed.length() <= 40
                    && !looksLikeProse(trimmed)
                    && lastKeptMeaningful != null
                    && lastKeptMeaningful.equals(trimmed)) {
                // 连续重复短标题只留一行
                continue;
            }
            kept.add(line);
            if (StringUtils.hasText(trimmed)) {
                lastKeptMeaningful = trimmed;
            }
        }
        return String.join("\n", kept);
    }

    /**
     * PDF：按 \\f 分页，丢掉目录页。
     */
    private static String stripTocPdfPages(String text) {
        String[] pages = text.split("\\f", -1);
        StringBuilder sb = new StringBuilder();
        for (String page : pages) {
            if (isTocPage(page)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\f');
            }
            sb.append(page);
        }
        return sb.toString();
    }

    /**
     * 目录区里常见的短章节名（无句读、无字段冒号），用于纯文本目录跳过。
     */
    static boolean looksLikeTocShortTitle(String line) {
        if (!StringUtils.hasText(line) || line.length() > 40) {
            return false;
        }
        String t = line.trim();
        if (t.contains("。") || t.contains("！") || t.contains("？") || t.contains("；")) {
            return false;
        }
        // 含「原料/用法/功能」等正文字段则不是目录短标题
        if (t.contains("：") || t.contains(":")) {
            return false;
        }
        return t.length() <= 30;
    }

    private static boolean looksLikeProse(String line) {
        if (line.length() < 20) {
            return false;
        }
        if (isEbookNavLine(line) || isTocLine(line)) {
            return false;
        }
        if (line.matches(".*[。！？；].*") && !line.matches(".*\\d{1,4}\\s*$")) {
            return true;
        }
        return line.length() >= 40;
    }

    private static String stripLeadingHashes(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceFirst("^#{1,6}\\s*", "").trim();
    }

    private static String extractHeadingTitle(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            if (isTocTitle(trimmed)) {
                return trimmed;
            }
            return null;
        }
        String title = stripLeadingHashes(trimmed);
        return title.isEmpty() ? null : title;
    }

    private static Integer extractHeadingLevel(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            if (isTocTitle(trimmed)) {
                return 1;
            }
            return null;
        }
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6) {
            return null;
        }
        if (level >= trimmed.length() || trimmed.charAt(level) != ' ') {
            return null;
        }
        return level;
    }
}
