package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.DocumentChunker;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REQ-03：超长切片再切 — 节内边界、part 元数据、sectionTitle 锚点回填。
 */
final class OversizePartSplitter {

    private static final Pattern EMBEDDED_HEADER = Pattern.compile("(?m)^(#{1,2})\\s+(.+)$");
    private static final AtomicInteger PARENT_SEQ = new AtomicInteger();

    private OversizePartSplitter() {
    }

    static List<DocumentChunk> split(List<DocumentChunk> chunks, int chunkSize, int overlap) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        DocumentChunker splitter = DocumentChunker.algorithmOnly();
        List<DocumentChunk> out = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String raw = ShellChunkProcessor.stripTrailingHorizontalRules(chunk.getContent());
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            // 先按正文内嵌 H1/H2 切开，避免跨下一专篇 HEADER
            List<SectionSpan> spans = splitByEmbeddedHeaders(raw, chunk.getSectionTitle());
            for (SectionSpan span : spans) {
                out.addAll(splitOneSpan(chunk, span, chunkSize, overlap, splitter));
            }
        }
        return out;
    }

    private static List<DocumentChunk> splitOneSpan(DocumentChunk parent, SectionSpan span,
                                                   int chunkSize, int overlap, DocumentChunker splitter) {
        String content = span.body == null ? "" : span.body.trim();
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String baseTitle = resolveBaseTitle(parent, span.headerTitle);
        String parentKey = resolveParentKey(parent);

        if (content.length() <= chunkSize) {
            String title = ensureTitle(baseTitle, parent, content);
            Map<String, Object> meta = copyMeta(parent);
            return List.of(new DocumentChunk(content, parent.getPageNum(), title, meta));
        }

        List<DocumentChunk> parts = splitter.wrapRecursive(content, chunkSize, overlap);
        if (parts.isEmpty()) {
            String title = ensureTitle(baseTitle, parent, content);
            return List.of(new DocumentChunk(content, parent.getPageNum(), title, copyMeta(parent)));
        }
        int total = parts.size();
        List<DocumentChunk> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String partBody = ShellChunkProcessor.stripTrailingHorizontalRules(parts.get(i).getContent());
            if (!StringUtils.hasText(partBody)) {
                continue;
            }
            int partIndex = i + 1;
            String title = ensureTitle(baseTitle, parent, partBody);
            if (total > 1) {
                title = appendPartSuffix(title, partIndex, total);
            }
            Map<String, Object> meta = copyMeta(parent);
            meta.put("part_index", partIndex);
            meta.put("partIndex", partIndex);
            meta.put("part_total", total);
            meta.put("partTotal", total);
            meta.put("parent_chunk_id", parentKey);
            meta.put("parentChunkId", parentKey);
            out.add(new DocumentChunk(partBody, parent.getPageNum(), title, meta));
        }
        return out;
    }

    /**
     * 在正文内按 # / ## 切开；标题行不写入 content。
     */
    static List<SectionSpan> splitByEmbeddedHeaders(String content, String fallbackTitle) {
        List<SectionSpan> spans = new ArrayList<>();
        if (!StringUtils.hasText(content)) {
            return spans;
        }
        Matcher m = EMBEDDED_HEADER.matcher(content);
        List<int[]> hits = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            hits.add(new int[]{m.start(), m.end()});
            titles.add(m.group(2).trim());
        }
        if (hits.isEmpty()) {
            spans.add(new SectionSpan(fallbackTitle, content));
            return spans;
        }
        if (hits.get(0)[0] > 0) {
            String preamble = content.substring(0, hits.get(0)[0]).trim();
            if (StringUtils.hasText(preamble)) {
                spans.add(new SectionSpan(fallbackTitle, preamble));
            }
        }
        for (int i = 0; i < hits.size(); i++) {
            int bodyStart = hits.get(i)[1];
            int bodyEnd = i + 1 < hits.size() ? hits.get(i + 1)[0] : content.length();
            String body = content.substring(bodyStart, bodyEnd).trim();
            if (StringUtils.hasText(body)) {
                spans.add(new SectionSpan(titles.get(i), body));
            }
        }
        return spans;
    }

    static String resolveBaseTitle(DocumentChunk parent, String spanHeader) {
        if (StringUtils.hasText(spanHeader)) {
            String article = firstNonBlank(
                    parent.metaString("header_path"),
                    parent.getSectionTitle(),
                    parent.metaString("entry_name"));
            if (StringUtils.hasText(article) && !article.equals(spanHeader)
                    && !spanHeader.startsWith(article)) {
                return article + " / " + spanHeader;
            }
            return spanHeader;
        }
        return firstNonBlank(
                parent.getSectionTitle(),
                parent.metaString("header_path"),
                parent.metaString("entry_name"));
    }

    static String ensureTitle(String baseTitle, DocumentChunk parent, String content) {
        if (StringUtils.hasText(baseTitle)) {
            return baseTitle.trim();
        }
        String fromMeta = firstNonBlank(parent.metaString("header_path"), parent.metaString("entry_name"));
        if (StringUtils.hasText(fromMeta)) {
            return fromMeta.trim();
        }
        String fromBody = firstHeadingLikeLine(content);
        if (StringUtils.hasText(fromBody)) {
            return fromBody;
        }
        // 禁止 title/entryName/header_path 皆空的裸片
        return "未命名章节";
    }

    static String appendPartSuffix(String title, int partIndex, int partTotal) {
        String base = title == null ? "" : title.trim();
        // 避免重复追加 (i/n)
        if (base.matches(".*\\(\\d+/\\d+\\)\\s*$")) {
            return base.replaceFirst("\\(\\d+/\\d+\\)\\s*$", "(" + partIndex + "/" + partTotal + ")");
        }
        return base + " (" + partIndex + "/" + partTotal + ")";
    }

    private static String resolveParentKey(DocumentChunk parent) {
        String existing = firstNonBlank(parent.metaString("parent_chunk_id"), parent.metaString("parentChunkId"),
                parent.metaString("parent_id"));
        if (StringUtils.hasText(existing)) {
            return existing + "#oversize-" + PARENT_SEQ.incrementAndGet();
        }
        return "oversize-" + PARENT_SEQ.incrementAndGet();
    }

    private static Map<String, Object> copyMeta(DocumentChunk parent) {
        return new LinkedHashMap<>(parent.getMetadata());
    }

    private static String firstHeadingLikeLine(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        for (String line : content.split("\\R", -1)) {
            String t = line == null ? "" : line.trim();
            if (!StringUtils.hasText(t) || ShellChunkProcessor.isHorizontalRuleLine(t)) {
                continue;
            }
            if (t.startsWith("#")) {
                return t.replaceFirst("^#{1,6}\\s*", "").trim();
            }
            if (t.length() <= 40 && !t.contains("。") && !t.startsWith("-")) {
                String bold = ShellChunkProcessor.stripBoldWrapper(t);
                if (StringUtils.hasText(bold) && bold.length() <= 40) {
                    return bold;
                }
            }
            break;
        }
        return "";
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

    static final class SectionSpan {
        final String headerTitle;
        final String body;

        SectionSpan(String headerTitle, String body) {
            this.headerTitle = headerTitle;
            this.body = body;
        }
    }
}
