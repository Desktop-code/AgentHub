package com.agenthub.rag.domain.service;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
// import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识切片门面。
 * <p>
 * 自 2026-09-14 起，主链路改为「IR → 结构原型识别 → 策略路由」
 * （见 {@link com.agenthub.rag.domain.service.ir.StructureAwareChunker}），
 * 不再按文件扩展名硬编码 FAQ/MD/PDF/DOCX 分支。
 * 下方旧的按文件类型切分方法<strong>不删除</strong>，供层级/叙述策略复用算法。
 */
public class DocumentChunker {

    static final List<String> DEFAULT_SEPS = List.of("\n## ", "\n### ", "\n\n", "。", "！", "？", "；", "\n", "，", " ");
    /**
     * 分隔符优先级：① 章节标题 ② 中文句读 ③ 换行/逗号；更细档由硬切承担。
     */
    static final List<String> MARKDOWN_SEPS = List.of(
            "\n## ", "\n### ",
            "。", "！", "？", "；",
            "\n", "，");
    static final List<String> PDF_SEPS = List.of("\n\n", "。", "！", "？", "；", "\n", "，");
    static final List<String> TXT_SEPS = List.of("\n\n", "。", "！", "？", "；", "\n", "，");
    /** 默认最小块长度（可被 metadata / 调用方覆盖） */
    public static final int DEFAULT_MIN_CHUNK_SIZE = 150;
    /** 最近一次 min-chunk 合并次数（单测 / 入库统计） */
    private static final ThreadLocal<Integer> LAST_MIN_MERGE_COUNT = ThreadLocal.withInitial(() -> 0);

    public static int lastMinMergeCount() {
        return LAST_MIN_MERGE_COUNT.get();
    }
    // 【当前无调用方 / 已注释保留】仅旧 splitDocx 使用，主链路已改 IR 路由
    // static final List<String> DOCX_SEPS = List.of("\n## ", "\n### ", "\n\n", "\n", "。");

    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");
    // 【当前无调用方 / 已注释保留】仅旧 tryFaq 使用，FAQ 改由 EntryBasedChunkStrategy 处理
    // private static final Pattern FAQ_PAIR = Pattern.compile(
    //         "(?ms)^(?:Q[:：]|问[:：]|【问】)\\s*(.+?)\\s*^(?:A[:：]|答[:：]|【答】)\\s*(.+?)(?=^(?:Q[:：]|问[:：]|【问】)|\\z)");

    private final RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter();
    private final com.agenthub.rag.domain.service.ir.StructureAwareChunker structureAwareChunker;

    /** 单测 / 无 Spring：内置默认 IR 链路。 */
    public DocumentChunker() {
        this.structureAwareChunker = com.agenthub.rag.domain.service.ir.StructureAwareChunker.createDefault();
    }

    /** 运行时注入 Spring 策略 Bean，新增结构原型只需加策略类。 */
    public DocumentChunker(com.agenthub.rag.domain.service.ir.StructureAwareChunker structureAwareChunker) {
        this.structureAwareChunker = structureAwareChunker != null
                ? structureAwareChunker
                : com.agenthub.rag.domain.service.ir.StructureAwareChunker.createDefault();
    }

    /**
     * 仅复用 Markdown/PDF/递归切分算法，不委托 IR，避免与 Hierarchical/Narrative 循环构造。
     */
    public static DocumentChunker algorithmOnly() {
        return new DocumentChunker(false);
    }

    private DocumentChunker(boolean unusedAlgorithmOnly) {
        this.structureAwareChunker = null;
    }

    /**
     * 结构感知切分入口（兼容原签名）。
     *
     * @param fileName  原始文件名，可空
     * @param text      解析后的正文
     * @param chunkSize 目标长度
     * @param overlap   重叠
     * @return 切片列表
     */
    public List<DocumentChunk> chunk(String fileName, String text, int chunkSize, int overlap) {
        return chunk(fileName, text, chunkSize, overlap, DEFAULT_MIN_CHUNK_SIZE);
    }

    public List<DocumentChunk> chunk(String fileName, String text, int chunkSize, int overlap, int minChunkSize) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        // ========== 以下旧逻辑已废弃（2026-09-14），注释原因：【改为按结构原型路由切片，不再按文件类型；迁移路径：StructureAwareChunker + Entry/Hierarchical/Narrative/Tabular 策略】 ==========
        // String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        // normalized = TocNoiseFilter.stripBeforeChunk(normalized);
        // if (!StringUtils.hasText(normalized)) {
        //     return List.of();
        // }
        // int size = Math.max(80, chunkSize);
        // int ov = Math.max(0, overlap);
        // String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        // List<DocumentChunk> faq = tryFaq(normalized, name);
        // if (!faq.isEmpty()) {
        //     return dropLowQuality(faq);
        // }
        // List<DocumentChunk> chunks;
        // if (name.endsWith(".md") || looksLikeMarkdown(normalized)) {
        //     chunks = splitMarkdown(normalized, size, ov);
        // } else if (name.endsWith(".pdf") || normalized.indexOf('\f') >= 0) {
        //     chunks = splitPdf(normalized, size, ov);
        // } else if (name.endsWith(".docx")) {
        //     chunks = splitDocx(normalized, size, ov);
        // } else {
        //     chunks = wrap(splitter.split(normalized, size, ov, TXT_SEPS), 0, "");
        // }
        // return dropLowQuality(chunks);
        // ========== 废弃代码结束 ==========
        return structureAwareChunker.chunk(fileName, text, chunkSize, overlap, minChunkSize);
    }

    /**
     * 丢掉目录型噪声切片；正文切片原样保留。
     * 【当前无调用方 / 已注释保留】旧按文件类型分支已废弃，现网噪声过滤在 IR 策略内完成。
     */
    /*
    private static List<DocumentChunk> dropLowQuality(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<DocumentChunk> kept = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || TocNoiseFilter.isLowQualityChunk(chunk.getContent())) {
                continue;
            }
            if (TocNoiseFilter.isTocTitle(chunk.getSectionTitle())) {
                continue;
            }
            kept.add(chunk);
        }
        return kept;
    }
    */

    /**
     * Markdown：按 MD 标题拆章节，超长章节内部递归切。
     * <p>
     * <b>content 只含正文</b>，sectionTitle 仅作元数据，禁止把标题拼进 content。
     */
    public List<DocumentChunk> splitMarkdown(String text, int chunkSize, int overlap) {
        return splitMarkdown(text, chunkSize, overlap, Collections.emptyMap(), DEFAULT_MIN_CHUNK_SIZE);
    }

    /**
     * @param titlePageMap 章节标题 → 页码（来自 IR HEADER.pageNum）；缺省为 0
     * @param minChunkSize 过短片合并到前一片的阈值（禁止跨章节）
     */
    public List<DocumentChunk> splitMarkdown(String text, int chunkSize, int overlap,
                                             Map<String, Integer> titlePageMap, int minChunkSize) {
        Map<String, Integer> pages = titlePageMap == null ? Collections.emptyMap() : titlePageMap;
        int minSize = Math.max(0, minChunkSize);
        List<Section> sections = splitSections(text);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            String title = section.title;
            if (TocNoiseFilter.isTocTitle(title)) {
                continue;
            }
            String body = section.body.trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }
            body = dropTocLines(body);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            int pageNum = resolvePageNum(title, pages);
            // 正文不超过 chunkSize：整段入库，content 不以标题开头
            if (body.length() <= chunkSize) {
                chunks.add(new DocumentChunk(body, pageNum, title));
                continue;
            }
            List<String> parts = splitter.split(body, chunkSize, overlap, MARKDOWN_SEPS);
            if (parts.isEmpty()) {
                chunks.add(new DocumentChunk(body, pageNum, title));
                continue;
            }
            for (String part : parts) {
                chunks.add(new DocumentChunk(part, pageNum, title));
            }
        }
        return mergeSmallChunks(chunks, minSize);
    }

    private static int resolvePageNum(String title, Map<String, Integer> titlePageMap) {
        if (titlePageMap == null || titlePageMap.isEmpty() || !StringUtils.hasText(title)) {
            return 0;
        }
        Integer p = titlePageMap.get(title);
        if (p != null && p > 0) {
            return p;
        }
        // 宽松匹配：去掉首尾空白后比较
        String key = title.trim();
        p = titlePageMap.get(key);
        return p == null ? 0 : Math.max(0, p);
    }

    /**
     * 长度 &lt; minChunkSize 的碎片强制合并到<strong>前一个</strong>同章节 chunk；禁止跨章节合并。
     */
    static List<DocumentChunk> mergeSmallChunks(List<DocumentChunk> chunks, int minChunkSize) {
        LAST_MIN_MERGE_COUNT.set(0);
        if (chunks == null || chunks.isEmpty() || minChunkSize <= 0) {
            return chunks == null ? List.of() : chunks;
        }
        List<DocumentChunk> result = new ArrayList<>();
        int merges = 0;
        for (DocumentChunk c : chunks) {
            if (c == null || !StringUtils.hasText(c.getContent())) {
                continue;
            }
            if (result.isEmpty()) {
                result.add(c);
                continue;
            }
            // 当前片过短 → 并入上一片（同章节）
            if (c.getContent().length() < minChunkSize
                    && sameSection(result.get(result.size() - 1), c)) {
                DocumentChunk last = result.remove(result.size() - 1);
                result.add(mergeChunks(last, c));
                merges++;
                continue;
            }
            // 上一片过短且与当前同章节 → 把当前并入上一片
            DocumentChunk prev = result.get(result.size() - 1);
            if (prev.getContent().length() < minChunkSize && sameSection(prev, c)) {
                result.set(result.size() - 1, mergeChunks(prev, c));
                merges++;
                continue;
            }
            result.add(c);
        }
        LAST_MIN_MERGE_COUNT.set(merges);
        return result;
    }

    private static boolean sameSection(DocumentChunk a, DocumentChunk b) {
        String ta = a.getSectionTitle() == null ? "" : a.getSectionTitle();
        String tb = b.getSectionTitle() == null ? "" : b.getSectionTitle();
        return ta.equals(tb);
    }

    private static DocumentChunk mergeChunks(DocumentChunk a, DocumentChunk b) {
        String contentA = a.getContent() == null ? "" : a.getContent().trim();
        String contentB = b.getContent() == null ? "" : b.getContent().trim();
        String merged;
        if (!StringUtils.hasText(contentB)) {
            merged = contentA;
        } else if (!StringUtils.hasText(contentA)) {
            merged = contentB;
        } else if (contentB.startsWith(contentA)) {
            merged = contentB;
        } else {
            merged = contentA + "\n" + contentB;
        }
        int page = a.getPageNum() > 0 ? a.getPageNum() : b.getPageNum();
        String title = StringUtils.hasText(a.getSectionTitle()) ? a.getSectionTitle() : b.getSectionTitle();
        Map<String, Object> meta = new java.util.LinkedHashMap<>(a.getMetadata());
        meta.putAll(b.getMetadata());
        return new DocumentChunk(merged, page, title, meta);
    }

    private static String dropTocLines(String body) {
        String[] lines = body.split("\\R", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (TocNoiseFilter.isTocLine(line == null ? "" : line.trim())) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).trim();
    }

    /**
     * PDF：按 \\f 分页。供 {@link com.agenthub.rag.domain.service.ir.NarrativeChunkStrategy} 复用。
     */
    public List<DocumentChunk> splitPdf(String text, int chunkSize, int overlap) {
        String[] pages = text.split("\\f", -1);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            String page = pages[i].trim();
            if (page.isEmpty() || TocNoiseFilter.isTocPage(page)) {
                continue;
            }
            page = dropTocLines(page);
            if (!StringUtils.hasText(page)) {
                continue;
            }
            int pageNum = i + 1;
            if (page.length() <= chunkSize) {
                chunks.add(new DocumentChunk(page, pageNum, ""));
                continue;
            }
            for (String part : splitter.split(page, chunkSize, overlap, PDF_SEPS)) {
                chunks.add(new DocumentChunk(part, pageNum, ""));
            }
        }
        return chunks;
    }

    /**
     * Word：解析阶段已把标题样式写成 Markdown，这里按标题 + 段落递归切。
     * 【当前无调用方 / 已注释保留】供兼容 / 叙述型 DOCX 扩展对照。
     */
    /*
    List<DocumentChunk> splitDocx(String text, int chunkSize, int overlap) {
        if (looksLikeMarkdown(text)) {
            return splitMarkdown(text, chunkSize, overlap);
        }
        return wrap(splitter.split(text, chunkSize, overlap, DOCX_SEPS), 0, "");
    }
    */

    /**
     * 识别 Q/A 块。
     * 【当前无调用方 / 已注释保留】FAQ 改由 EntryBasedChunkStrategy 处理。
     */
    /*
    List<DocumentChunk> tryFaq(String text, String fileName) {
        boolean hint = fileName.contains("faq") || fileName.contains("问答");
        Matcher matcher = FAQ_PAIR.matcher(text);
        List<DocumentChunk> pairs = new ArrayList<>();
        while (matcher.find()) {
            String q = matcher.group(1).trim();
            String a = matcher.group(2).trim();
            if (q.isEmpty() && a.isEmpty()) {
                continue;
            }
            pairs.add(new DocumentChunk("问：" + q + "\n答：" + a, 0, q));
        }
        if (pairs.isEmpty()) {
            return List.of();
        }
        if (!hint && pairs.size() < 2) {
            return List.of();
        }
        return pairs;
    }
    */

    static List<Section> splitSections(String text) {
        Matcher matcher = HEADING.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            titles.add(matcher.group(2).trim());
        }
        List<Section> sections = new ArrayList<>();
        if (starts.isEmpty()) {
            sections.add(new Section("", text.trim()));
            return sections;
        }
        if (starts.get(0) > 0) {
            String preamble = text.substring(0, starts.get(0)).trim();
            if (!preamble.isEmpty()) {
                sections.add(new Section("", preamble));
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String block = text.substring(from, to);
            String title = titles.get(i);
            String body = HEADING.matcher(block).replaceFirst("").trim();
            sections.add(new Section(title, body));
        }
        return sections;
    }

    /**
     * 【当前无调用方 / 已注释保留】仅旧 splitDocx 判断「看起来像 Markdown」。
     */
    /*
    static boolean looksLikeMarkdown(String text) {
        return HEADING.matcher(text).find();
    }
    */

    /**
     * @deprecated 禁止把标题拼进 content；仅保留兼容入口，等价于返回 body。
     */
    @Deprecated
    public static String prependTitle(String title, String body) {
        return body == null ? "" : body.trim();
    }

    /** 叙述型纯文本：递归字符切分。供 IR Narrative 策略复用。 */
    public List<DocumentChunk> wrapRecursive(String text, int chunkSize, int overlap) {
        return wrap(splitter.split(text, chunkSize, overlap, TXT_SEPS), 0, "");
    }

    private static List<DocumentChunk> wrap(List<String> texts, int pageNum, String sectionTitle) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (String text : texts) {
            chunks.add(new DocumentChunk(text, pageNum, sectionTitle));
        }
        return chunks;
    }

    static final class Section {
        private final String title;
        private final String body;

        private Section(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }
}
