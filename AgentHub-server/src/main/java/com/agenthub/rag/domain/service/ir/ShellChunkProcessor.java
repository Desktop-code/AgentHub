package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REQ-03 / REQ-03b：空壳 / 装饰片丢弃或合并；合并不得跨专篇；剥离 content 末尾水平分隔线。
 */
final class ShellChunkProcessor {

    /** 整行只有 - / * / _（≥3），不含表分隔 |---| */
    private static final Pattern HR_LINE = Pattern.compile("^(?:-{3,}|\\*{3,}|_{3,})$");
    private static final Pattern ONLY_BOLD = Pattern.compile("^\\*\\*[^*\\n]+\\*\\*$");
    private static final Pattern HAS_LIST = Pattern.compile("(?m)^\\s*[-*•]\\s+\\S");
    private static final Pattern HAS_TABLE = Pattern.compile("\\|");
    /** 专篇题：01-xxx / 第N篇 / 第12篇 xxx */
    private static final Pattern ARTICLE_TITLE = Pattern.compile(
            "^(?:\\d{2}-\\S.*|第[零〇一二三四五六七八九十百千\\d]+篇\\S*)$");
    private static final Pattern CORPUS_PREAMBLE = Pattern.compile(
            "(?s)^\\s*>.*(?:合并|corpus-|由.{0,40}篇).*$");

    /** 默认空壳阈值（有效正文长度） */
    static final int DEFAULT_SHELL_MIN_CHARS = 60;

    private ShellChunkProcessor() {
    }

    /**
     * 丢弃纯分隔线/纯粗体副标题；过短空壳在<strong>同专篇</strong>内并入下一片（保留下一片 sectionTitle）。
     * <p>
     * REQ-03b：专篇锚点不同、或下一片 header_level 更靠近根、或夹水平线边界 → 禁止合并；
     * 禁止合并的空壳（有效正文 &lt; 阈值且无表无列表）直接丢弃。
     */
    static List<DocumentChunk> collapse(List<DocumentChunk> chunks, int shellMinChars, int shortMinChars) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int shellMin = Math.max(20, shellMinChars);
        int shortMin = Math.max(shellMin, shortMinChars);

        List<DocumentChunk> normalized = new ArrayList<>();
        for (DocumentChunk c : chunks) {
            if (c == null) {
                continue;
            }
            String body = stripTrailingHorizontalRules(c.getContent());
            body = dropLeadingDecorativeLines(body);
            if (!StringUtils.hasText(body) || isDecorativeOnly(body) || isCorpusPreamble(body)) {
                continue;
            }
            normalized.add(new DocumentChunk(body, c.getPageNum(), c.getSectionTitle(),
                    new LinkedHashMap<>(c.getMetadata())));
        }

        List<DocumentChunk> out = new ArrayList<>();
        int i = 0;
        while (i < normalized.size()) {
            DocumentChunk cur = normalized.get(i);
            String body = cur.getContent();
            boolean shell = isShell(body, shellMin);
            boolean tooShort = effectiveLength(body) < shortMin && !hasListOrTable(body);

            if ((shell || tooShort) && i + 1 < normalized.size()) {
                DocumentChunk next = normalized.get(i + 1);
                if (canMerge(cur, next)) {
                    normalized.set(i + 1, mergeIntoNext(cur, next));
                    i++;
                    continue;
                }
                // 跨专篇：空壳丢弃；非空壳短片单独保留
                if (shell) {
                    i++;
                    continue;
                }
                out.add(cur);
                i++;
                continue;
            }
            if (shell && out.isEmpty() && i + 1 >= normalized.size()) {
                i++;
                continue;
            }
            if (shell && !out.isEmpty() && i + 1 >= normalized.size()) {
                DocumentChunk prev = out.get(out.size() - 1);
                if (canMerge(prev, cur)) {
                    out.set(out.size() - 1, mergeKeepPrevTitle(prev, cur));
                }
                // 跨专篇末片空壳：丢弃（不并入上一专篇）
                i++;
                continue;
            }
            out.add(cur);
            i++;
        }
        return out;
    }

    /**
     * 同专篇才允许合并；下一片更靠近根的 HEADER、或内容夹整行 HR → 禁止。
     */
    static boolean canMerge(DocumentChunk left, DocumentChunk right) {
        if (left == null || right == null) {
            return false;
        }
        if (!sameArticleAnchor(left, right)) {
            return false;
        }
        if (crossesToHigherHeader(left, right)) {
            return false;
        }
        if (hasHorizontalBoundary(left, right)) {
            return false;
        }
        return true;
    }

    /**
     * 专篇锚点：article_title → header_path 顶层 → 匹配 01-/第N篇 的 title → sectionTitle 弱锚点。
     */
    static String articleAnchor(DocumentChunk chunk) {
        if (chunk == null) {
            return "";
        }
        String fromMeta = firstNonBlank(
                chunk.metaString("article_title"),
                chunk.metaString("articleTitle"));
        if (StringUtils.hasText(fromMeta)) {
            return normalizeAnchor(fromMeta);
        }
        String path = chunk.metaString("header_path");
        if (StringUtils.hasText(path) && path.contains(" / ")) {
            return normalizeAnchor(path.substring(0, path.indexOf(" / ")));
        }
        String title = firstNonBlank(chunk.getSectionTitle(), path, chunk.metaString("entry_name"));
        if (looksLikeArticleTitle(title)) {
            return normalizeAnchor(title);
        }
        // 弱锚点：无专篇标记时用完整 sectionTitle，不同节默认不合并（更安全）
        return normalizeAnchor(title);
    }

    static boolean sameArticleAnchor(DocumentChunk a, DocumentChunk b) {
        String aa = articleAnchor(a);
        String bb = articleAnchor(b);
        if (!StringUtils.hasText(aa) && !StringUtils.hasText(bb)) {
            // 双方皆无锚点（叙述型）：允许合并
            return true;
        }
        return aa.equals(bb);
    }

    static boolean looksLikeArticleTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String t = title.trim();
        return ARTICLE_TITLE.matcher(t).matches()
                || t.matches("^第[零〇一二三四五六七八九十百千\\d]+篇.*");
    }

    /**
     * 下一片 header_level 更小（更靠近根，如 ###→#）→ 跨大节，禁止合并。
     */
    static boolean crossesToHigherHeader(DocumentChunk cur, DocumentChunk next) {
        int curLv = headerLevel(cur);
        int nextLv = headerLevel(next);
        if (curLv == Integer.MAX_VALUE || nextLv == Integer.MAX_VALUE) {
            return false;
        }
        return nextLv < curLv;
    }

    /**
     * 左片末或右片首存在整行 HR（非表行）→ 篇界。
     * 注：stripTrailing 已剥尾部 HR，此处检查正文内残留及右片开头。
     */
    static boolean hasHorizontalBoundary(DocumentChunk left, DocumentChunk right) {
        if (right != null && contentStartsWithHr(right.getContent())) {
            return true;
        }
        if (left != null && contentEndsWithHrBeforeStrip(left.getContent())) {
            return true;
        }
        return false;
    }

    static boolean isCorpusPreamble(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String t = content.trim();
        if (!t.startsWith(">")) {
            return false;
        }
        return CORPUS_PREAMBLE.matcher(t).matches()
                || (t.contains("合并") || t.toLowerCase().contains("corpus-")
                || (t.contains("由") && t.contains("篇")));
    }

    static String stripTrailingHorizontalRules(String content) {
        if (!StringUtils.hasText(content)) {
            return content == null ? "" : content;
        }
        String[] lines = content.split("\\R", -1);
        int end = lines.length;
        while (end > 0) {
            String t = lines[end - 1] == null ? "" : lines[end - 1].trim();
            if (!StringUtils.hasText(t) || isHorizontalRuleLine(t)) {
                end--;
                continue;
            }
            break;
        }
        if (end >= lines.length) {
            return content.trim();
        }
        if (end <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i] == null ? "" : lines[i]);
        }
        return sb.toString().trim();
    }

    static boolean isHorizontalRuleLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String t = line.trim();
        // 表分隔 |---| 明确排除
        if (t.contains("|")) {
            return false;
        }
        return HR_LINE.matcher(t).matches();
    }

    static String stripBoldWrapper(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        String t = line.trim();
        if (ONLY_BOLD.matcher(t).matches()) {
            return t.substring(2, t.length() - 2).trim();
        }
        return t;
    }

    static boolean isDecorativeOnly(String content) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        String[] lines = content.split("\\R", -1);
        boolean any = false;
        for (String raw : lines) {
            String t = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(t) || isHorizontalRuleLine(t)) {
                continue;
            }
            if (ONLY_BOLD.matcher(t).matches()) {
                any = true;
                continue;
            }
            return false;
        }
        return any || effectiveLength(content) == 0;
    }

    static boolean isShell(String content, int shellMinChars) {
        if (hasListOrTable(content)) {
            return false;
        }
        return effectiveLength(content) < shellMinChars;
    }

    static int effectiveLength(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : content.split("\\R", -1)) {
            String t = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(t) || isHorizontalRuleLine(t)) {
                continue;
            }
            if (ONLY_BOLD.matcher(t).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString().replaceAll("\\s+", "").length();
    }

    private static boolean hasListOrTable(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        return HAS_LIST.matcher(content).find() || content.chars().filter(c -> c == '|').count() >= 2
                && HAS_TABLE.matcher(content).find();
    }

    private static boolean contentStartsWithHr(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        for (String raw : content.split("\\R", -1)) {
            String t = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(t)) {
                continue;
            }
            return isHorizontalRuleLine(t);
        }
        return false;
    }

    private static boolean contentEndsWithHrBeforeStrip(String content) {
        // strip 前已调用 stripTrailing；此处检测「正文后仍残留的独立 HR 行」（中间篇界少见）
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String[] lines = content.split("\\R", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (!StringUtils.hasText(t)) {
                continue;
            }
            return isHorizontalRuleLine(t);
        }
        return false;
    }

    private static int headerLevel(DocumentChunk chunk) {
        if (chunk == null) {
            return Integer.MAX_VALUE;
        }
        Object v = chunk.getMetadata().get("header_level");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String normalizeAnchor(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return "";
    }

    private static String dropLeadingDecorativeLines(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String[] lines = content.split("\\R", -1);
        int start = 0;
        while (start < lines.length) {
            String t = lines[start] == null ? "" : lines[start].trim();
            if (!StringUtils.hasText(t) || isHorizontalRuleLine(t) || ONLY_BOLD.matcher(t).matches()) {
                start++;
                continue;
            }
            break;
        }
        if (start == 0) {
            return content.trim();
        }
        if (start >= lines.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i] == null ? "" : lines[i]);
        }
        return stripTrailingHorizontalRules(sb.toString());
    }

    private static DocumentChunk mergeIntoNext(DocumentChunk shell, DocumentChunk next) {
        String a = shell.getContent() == null ? "" : shell.getContent().trim();
        String b = next.getContent() == null ? "" : next.getContent().trim();
        String merged = !StringUtils.hasText(a) ? b
                : (!StringUtils.hasText(b) ? a : a + "\n" + b);
        merged = stripTrailingHorizontalRules(merged);
        Map<String, Object> meta = new LinkedHashMap<>(next.getMetadata());
        // 保留下一片标题与专篇锚点
        return new DocumentChunk(merged, next.getPageNum() > 0 ? next.getPageNum() : shell.getPageNum(),
                next.getSectionTitle(), meta);
    }

    private static DocumentChunk mergeKeepPrevTitle(DocumentChunk prev, DocumentChunk shell) {
        String a = prev.getContent() == null ? "" : prev.getContent().trim();
        String b = shell.getContent() == null ? "" : shell.getContent().trim();
        String merged = !StringUtils.hasText(a) ? b
                : (!StringUtils.hasText(b) ? a : a + "\n" + b);
        merged = stripTrailingHorizontalRules(merged);
        Map<String, Object> meta = new LinkedHashMap<>(prev.getMetadata());
        return new DocumentChunk(merged, prev.getPageNum(), prev.getSectionTitle(), meta);
    }
}
