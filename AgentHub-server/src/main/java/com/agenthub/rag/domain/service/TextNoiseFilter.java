package com.agenthub.rag.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切分前清洗与切片后过滤。所属域：rag。
 * <p>
 * 参考 mall-swarm {@code TocNoiseFilter} 的通用部分，只保留与业务无关的规则：
 * 目录行、电子书翻页导航、重复空行、纯符号/纯数字噪声行。
 * 茶饮等业务词表一律不搬——切片清洗不该认识具体行业。
 */
public final class TextNoiseFilter {

    /** 典型目录行：标题 + 点线 + 页码 */
    private static final Pattern TOC_LINE = Pattern.compile("^.{2,80}?(?:\\.{2,}|…{1,}|·{2,})\\s*\\d{1,4}\\s*$");
    /** 目录标题行 */
    private static final Pattern TOC_TITLE = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:目\\s*录|目\\s*次|contents?|table\\s+of\\s+contents)\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** 电子书翻页导航整行 */
    private static final Pattern EBOOK_NAV = Pattern.compile(
            "^[\\s\\u3000]*(?:上一页|当前页|下一页|返回目录|回目录)\\s*[:：].*$");
    /** 纯符号行 */
    private static final Pattern PURE_SYMBOL = Pattern.compile("^[\\-=\\*#_~\\s]{3,}$");
    /** 纯页码行 */
    private static final Pattern PURE_PAGE_NO = Pattern.compile("^\\s*\\d{1,4}\\s*$");

    private TextNoiseFilter() {
    }

    /**
     * 切分前清洗：统一换行、去目录行/导航行/纯符号行、压缩多余空行。
     *
     * @param text 解析后的原始文本
     * @return 清洗后文本；入参空则返回空串
     */
    public static String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        int blankRun = 0;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.replace("\u00A0", " ").stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                // 最多保留一个空行：段落边界仍在，但不让空行占满切片预算
                if (blankRun == 0) {
                    kept.add("");
                }
                blankRun++;
                continue;
            }
            blankRun = 0;
            if (EBOOK_NAV.matcher(trimmed).matches()
                    || PURE_SYMBOL.matcher(trimmed).matches()
                    || PURE_PAGE_NO.matcher(trimmed).matches()
                    || TOC_LINE.matcher(trimmed).matches()) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).trim();
    }

    /**
     * 切片是否应丢弃：去空白后为空，或仍是目录标题。
     *
     * @param content 切片正文
     * @return true 表示无检索价值
     */
    public static boolean isNoiseChunk(String content) {
        if (content == null) {
            return true;
        }
        String text = content.trim();
        if (text.isEmpty()) {
            return true;
        }
        return TOC_TITLE.matcher(text).matches();
    }

    /**
     * 目录标题判断，供按标题切分的场景使用。
     *
     * @param title 章节标题
     * @return true 表示是目录本身
     */
    public static boolean isTocTitle(String title) {
        return title != null && TOC_TITLE.matcher(title.trim()).matches();
    }
}
