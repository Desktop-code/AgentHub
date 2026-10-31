package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.DocumentChunker;
import com.agenthub.rag.domain.service.TocNoiseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 层级型：复用 {@link DocumentChunker#splitMarkdown} 的标题切分算法，并保留 HEADER 页码。
 */
public class HierarchicalChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalChunkStrategy.class);

    private final DocumentChunker documentChunker = DocumentChunker.algorithmOnly();

    @Override
    public DocumentType supportedType() {
        return DocumentType.HIERARCHICAL;
    }

    @Override
    public List<DocumentChunk> chunk(StructuredDocument doc) {
        long headerCount = doc.getBlocks().stream().filter(b -> b.getBlockType() == BlockType.HEADER).count();
        String text = toMarkdownLike(doc);
        int mdHeaders = countMdHeaders(text);
        log.info("[IR][{}] Hierarchical toMarkdownLike headersInIr={} mdHeadingLines={} mdChars={} titlePageMap={}",
                DocumentStructurer.BUILD_TAG, headerCount, mdHeaders, text.length(),
                buildTitlePageMap(doc).size());
        if (headerCount == 0) {
            log.warn("[IR][{}] Hierarchical 收到 0 个 HEADER：sectionTitle 将全空，请检查 isValidTitle/filterByStatistics",
                    DocumentStructurer.BUILD_TAG);
        }
        int size = doc.chunkSize();
        int ov = doc.chunkOverlap();
        int minSize = doc.minChunkSize();
        Map<String, Integer> titlePageMap = buildTitlePageMap(doc);
        List<DocumentChunk> raw = documentChunker.splitMarkdown(text, size, ov, titlePageMap, minSize);
        List<DocumentChunk> out = new ArrayList<>();
        String fileName = doc.getFileName();
        // REQ-03b：跟踪最近专篇级 H1，供空壳合并门禁使用
        String currentArticle = "";
        for (DocumentChunk c : raw) {
            if (TocNoiseFilter.isLowQualityChunk(c.getContent())) {
                continue;
            }
            // REQ-03：剥离节末 ---，纯装饰粗体不单独成片（后续 ShellChunkProcessor 再兜底）
            String body = ShellChunkProcessor.stripTrailingHorizontalRules(c.getContent());
            if (!StringUtils.hasText(body) || ShellChunkProcessor.isDecorativeOnly(body)) {
                continue;
            }
            int level = guessLevel(c.getSectionTitle(), doc);
            String title = c.getSectionTitle() == null ? "" : c.getSectionTitle().trim();
            if (level <= 1 || ShellChunkProcessor.looksLikeArticleTitle(title)) {
                currentArticle = title;
            } else if (!StringUtils.hasText(currentArticle) && StringUtils.hasText(title)) {
                currentArticle = title;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("file_name", fileName);
            meta.put("block_type", BlockType.HEADER.name());
            meta.put("source_type", DocumentType.HIERARCHICAL.name());
            meta.put("header_path", title);
            meta.put("header_level", level);
            if (StringUtils.hasText(currentArticle)) {
                meta.put("article_title", currentArticle);
            }
            out.add(new DocumentChunk(body, c.getPageNum(), title, meta));
        }
        return out;
    }

    /**
     * HEADER.content → pageNum（首次出现；pageNum&gt;0 才记）。
     */
    static Map<String, Integer> buildTitlePageMap(StructuredDocument doc) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (doc == null || doc.getBlocks() == null) {
            return map;
        }
        for (StructuredBlock b : doc.getBlocks()) {
            if (b == null || b.getBlockType() != BlockType.HEADER) {
                continue;
            }
            String title = b.getContent();
            if (!StringUtils.hasText(title) || b.getPageNum() <= 0) {
                continue;
            }
            map.putIfAbsent(title.trim(), b.getPageNum());
        }
        return map;
    }

    /** package 可见，便于单测验证 HEADER→Markdown 拼接。 */
    static String toMarkdownLike(StructuredDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.HEADER) {
                int lv = Math.max(1, Math.min(6, b.getLevel() <= 0 ? 2 : b.getLevel()));
                sb.append("\n").append("#".repeat(lv)).append(' ').append(nvl(b.getContent())).append('\n');
            } else if (StringUtils.hasText(b.getContent())) {
                sb.append(b.getContent()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static int countMdHeaders(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int n = 0;
        for (String line : text.split("\n")) {
            if (line.matches("^#{1,6}\\s+.+$")) {
                n++;
            }
        }
        return n;
    }

    private static int guessLevel(String title, StructuredDocument doc) {
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.HEADER && title != null && title.equals(b.getContent())) {
                return b.getLevel();
            }
        }
        return 2;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
