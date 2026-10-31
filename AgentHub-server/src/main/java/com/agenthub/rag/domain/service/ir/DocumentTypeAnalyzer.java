package com.agenthub.rag.domain.service.ir;

import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别结构原型：层级 / 表格 / 条目 / 叙述。
 * <p>
 * REQ-02：假 ENTRY 不计分；合集 / 多 --- / 标题密且非真 FAQ → 强制 {@link DocumentType#HIERARCHICAL}。
 */
public class DocumentTypeAnalyzer {

    private static final Pattern MD = Pattern.compile("(?m)^#{1,6}\\s+");
    /** 仅统计一级/二级 ATX，用于合集强制判定 */
    private static final Pattern ATX_H12 = Pattern.compile("(?m)^#{1,2}\\s+\\S");
    private static final Pattern NUMBERED = Pattern.compile("(?m)^(?:\\d+\\.){1,4}\\s+\\S");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^(?:-{3,}|\\*{3,}|_{3,})$");
    private static final Pattern FAQ_MARKER = Pattern.compile(
            "(?m)^(?:Q[:：]|A[:：]|问[:：]|答[:：]|【问】|【答】)");
    private static final Pattern CORPUS_NAME = Pattern.compile(
            "合集|corpus|合并|合订", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 真 FAQ：标记行数达到此值则不强制 HIERARCHICAL */
    private static final int STRONG_FAQ_MARKER_MIN = 4;
    /** 合集强制：独立 --- / *** / ___ 分段下限 */
    private static final int CORPUS_HR_MIN = 3;
    /** 合集强制：#/## 较多且 FAQ 极少 */
    private static final int CORPUS_ATX_MIN = 5;
    private static final int CORPUS_FAQ_MARKER_MAX = 2;

    public DocumentType analyze(StructuredDocument doc) {
        if (doc == null || doc.getBlocks() == null || doc.getBlocks().isEmpty()) {
            return fallbackByFileName(doc);
        }
        String fileName = doc.getFileName() == null ? "" : doc.getFileName().toLowerCase(Locale.ROOT);

        // REQ-02：合集强制层级（真 FAQ 豁免）；优先于弱 ENTRY→ENTRY_BASED
        if (shouldForceHierarchical(doc)) {
            return DocumentType.HIERARCHICAL;
        }

        if (hasMarkdownHeaders(doc) || hasNumberedHierarchy(doc)) {
            // 中药手册偶发带 # 标题，高质量条目特征更强时仍走条目型
            if (hasRepeatedEntryPattern(doc) && entryScore(doc) >= 2) {
                return DocumentType.ENTRY_BASED;
            }
            return DocumentType.HIERARCHICAL;
        }
        if (hasTablePattern(doc) || fileName.endsWith(".csv") || fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            return DocumentType.TABULAR;
        }
        if (hasRepeatedEntryPattern(doc)) {
            return DocumentType.ENTRY_BASED;
        }
        return DocumentType.NARRATIVE;
    }

    /**
     * 合集 / 多分隔 / 标题密且非真 FAQ → 强制 HIERARCHICAL。
     * 真 FAQ（大量 问/答）不强制，避免误伤。
     */
    boolean shouldForceHierarchical(StructuredDocument doc) {
        if (doc == null) {
            return false;
        }
        if (isStrongFaq(doc)) {
            return false;
        }
        String fileName = doc.getFileName() == null ? "" : doc.getFileName();
        if (CORPUS_NAME.matcher(fileName).find()) {
            return true;
        }
        String joined = join(doc);
        if (countHorizontalRules(joined) >= CORPUS_HR_MIN) {
            return true;
        }
        // IR 的 HEADER.content 不含 #，按 level≤2 计数；并补扫正文残留 ATX
        int atx = countLevel12Headers(doc) + countMatches(ATX_H12, joined);
        int faq = countMatches(FAQ_MARKER, joined);
        return atx >= CORPUS_ATX_MIN && faq <= CORPUS_FAQ_MARKER_MAX;
    }

    static int countLevel12Headers(StructuredDocument doc) {
        if (doc == null || doc.getBlocks() == null) {
            return 0;
        }
        int n = 0;
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.HEADER && b.getLevel() > 0 && b.getLevel() <= 2) {
                n++;
            }
        }
        return n;
    }

    /**
     * 真 FAQ：正文 FAQ 标记充足，或存在多个「答」字段 / 高质量问答 ENTRY。
     */
    boolean isStrongFaq(StructuredDocument doc) {
        String joined = join(doc);
        if (countMatches(FAQ_MARKER, joined) >= STRONG_FAQ_MARKER_MIN) {
            return true;
        }
        long answerFields = 0;
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.FIELD && "答".equals(b.metaString("field_type"))) {
                answerFields++;
            }
        }
        if (answerFields >= 2) {
            return true;
        }
        return countHighQualityEntries(doc) >= 2 && answerFields >= 1;
    }

    boolean hasMarkdownHeaders(StructuredDocument doc) {
        long headers = doc.getBlocks().stream().filter(b -> b.getBlockType() == BlockType.HEADER).count();
        if (headers > 0) {
            return true;
        }
        String joined = join(doc);
        return MD.matcher(joined).find();
    }

    boolean hasNumberedHierarchy(StructuredDocument doc) {
        return doc.getBlocks().stream().anyMatch(b ->
                b.getBlockType() == BlockType.HEADER && b.getMetadata().containsKey("number"))
                || NUMBERED.matcher(join(doc)).find();
    }

    boolean hasTablePattern(StructuredDocument doc) {
        long tables = doc.getBlocks().stream().filter(b -> b.getBlockType() == BlockType.TABLE).count();
        if (tables > 0) {
            return true;
        }
        String[] lines = join(doc).split("\\n");
        int hits = 0;
        for (String line : lines) {
            if (DocumentStructurer.isTableLine(line)) {
                hits++;
            }
        }
        return lines.length > 0 && hits * 1.0 / lines.length >= 0.3 && hits >= 2;
    }

    /**
     * 仅高质量 ENTRY / 真 FIELD 参与「重复条目」判定（假 ENTRY 不计）。
     */
    boolean hasRepeatedEntryPattern(StructuredDocument doc) {
        long entries = countHighQualityEntries(doc);
        if (entries >= 2) {
            return true;
        }
        Map<String, Integer> fields = new HashMap<>();
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() != BlockType.FIELD) {
                continue;
            }
            String ft = b.metaString("field_type");
            if (StringUtils.hasText(ft)) {
                fields.merge(ft, 1, Integer::sum);
            }
        }
        int repeatedFields = 0;
        for (int c : fields.values()) {
            if (c >= 2) {
                repeatedFields++;
            }
        }
        return entries >= 1 && (repeatedFields >= 2 || fields.size() >= 2);
    }

    /**
     * 条目倾向分：高质量 ENTRY + 真 FIELD；假 ENTRY / 无 FIELD 的纯《书名》交叉引用计 0。
     */
    int entryScore(StructuredDocument doc) {
        int n = 0;
        Set<String> hqIds = highQualityEntryIds(doc);
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.ENTRY && hqIds.contains(b.getBlockId())) {
                n++;
            } else if (b.getBlockType() == BlockType.FIELD && StringUtils.hasText(b.metaString("field_type"))) {
                n++;
            }
        }
        return n;
    }

    long countHighQualityEntries(StructuredDocument doc) {
        return highQualityEntryIds(doc).size();
    }

    /**
     * 高质量 ENTRY：非列表/表行、title 不以见/详见/列表符开头，且（有子 FIELD 或显式问答形态）。
     * 仅 {@code 词条《书名》} 且无 FIELD → 不计分。
     */
    boolean isHighQualityEntry(StructuredBlock entry, StructuredDocument doc) {
        if (entry == null || entry.getBlockType() != BlockType.ENTRY) {
            return false;
        }
        String content = entry.getContent() == null ? "" : entry.getContent().trim();
        String name = firstText(entry.metaString("entry_name"), content);
        if (!StringUtils.hasText(name) && !StringUtils.hasText(content)) {
            return false;
        }
        if (DocumentStructurer.startsWithListMarker(name) || DocumentStructurer.startsWithListMarker(content)) {
            return false;
        }
        if (DocumentStructurer.endsWithSeeRef(name)) {
            return false;
        }
        if (DocumentStructurer.LIST_ITEM.matcher(content).matches() || DocumentStructurer.isTableLine(content)) {
            return false;
        }
        boolean hasField = hasChildField(doc, entry.getBlockId());
        boolean explicitFaq = "faq".equalsIgnoreCase(nvl(entry.metaString("entry_kind")));
        boolean bookRefOnly = DocumentStructurer.ENTRY_REF.matcher(content).matches();
        if (bookRefOnly && !hasField && !explicitFaq) {
            // 交叉引用形状或孤立书名引用、无字段 → 0 分
            return false;
        }
        return hasField || explicitFaq;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private Set<String> highQualityEntryIds(StructuredDocument doc) {
        Set<String> ids = new HashSet<>();
        if (doc == null || doc.getBlocks() == null) {
            return ids;
        }
        for (StructuredBlock b : doc.getBlocks()) {
            if (isHighQualityEntry(b, doc) && StringUtils.hasText(b.getBlockId())) {
                ids.add(b.getBlockId());
            }
        }
        return ids;
    }

    private static boolean hasChildField(StructuredDocument doc, String entryId) {
        if (!StringUtils.hasText(entryId) || doc == null || doc.getBlocks() == null) {
            return false;
        }
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.FIELD && entryId.equals(b.getParentId())) {
                return true;
            }
        }
        return false;
    }

    static int countHorizontalRules(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int n = 0;
        for (String line : text.split("\\n", -1)) {
            if (HORIZONTAL_RULE.matcher(line.trim()).matches()) {
                n++;
            }
        }
        return n;
    }

    private static int countMatches(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static DocumentType fallbackByFileName(StructuredDocument doc) {
        String name = doc == null || doc.getFileName() == null ? "" : doc.getFileName().toLowerCase(Locale.ROOT);
        if (CORPUS_NAME.matcher(name).find() || name.endsWith(".md")) {
            return DocumentType.HIERARCHICAL;
        }
        if (name.endsWith(".csv") || name.endsWith(".xlsx")) {
            return DocumentType.TABULAR;
        }
        return DocumentType.NARRATIVE;
    }

    private static String join(StructuredDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getContent() != null) {
                sb.append(b.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String firstText(String a, String b) {
        return StringUtils.hasText(a) ? a.trim() : (b == null ? "" : b.trim());
    }
}
