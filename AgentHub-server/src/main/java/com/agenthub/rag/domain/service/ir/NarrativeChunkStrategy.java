package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.DocumentChunker;
import com.agenthub.rag.domain.service.TocNoiseFilter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 叙述型：复用 PDF 分页切分与递归字符切分。
 */
public class NarrativeChunkStrategy implements ChunkStrategy {

    private final DocumentChunker documentChunker = DocumentChunker.algorithmOnly();

    @Override
    public DocumentType supportedType() {
        return DocumentType.NARRATIVE;
    }

    @Override
    public List<DocumentChunk> chunk(StructuredDocument doc) {
        int size = doc.chunkSize();
        int ov = doc.chunkOverlap();
        boolean paged = doc.getBlocks().stream().anyMatch(b -> b.getPageNum() > 0);
        String text = join(doc, paged);
        List<DocumentChunk> raw;
        if (paged || (doc.getFileName() != null && doc.getFileName().toLowerCase().endsWith(".pdf"))) {
            raw = documentChunker.splitPdf(text, size, ov);
        } else {
            raw = documentChunker.wrapRecursive(text, size, ov);
        }
        List<DocumentChunk> out = new ArrayList<>();
        for (DocumentChunk c : raw) {
            if (TocNoiseFilter.isLowQualityChunk(c.getContent())) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("file_name", doc.getFileName());
            meta.put("block_type", BlockType.PARAGRAPH.name());
            meta.put("source_type", DocumentType.NARRATIVE.name());
            out.add(new DocumentChunk(c.getContent(), c.getPageNum(), c.getSectionTitle(), meta));
        }
        return out;
    }

    private static String join(StructuredDocument doc, boolean paged) {
        if (!paged) {
            StringBuilder sb = new StringBuilder();
            for (StructuredBlock b : doc.getBlocks()) {
                if (StringUtils.hasText(b.getContent())) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(b.getContent());
                }
            }
            return sb.toString();
        }
        int maxPage = 1;
        for (StructuredBlock b : doc.getBlocks()) {
            maxPage = Math.max(maxPage, b.getPageNum());
        }
        StringBuilder[] pages = new StringBuilder[maxPage];
        for (int i = 0; i < maxPage; i++) {
            pages[i] = new StringBuilder();
        }
        for (StructuredBlock b : doc.getBlocks()) {
            int p = Math.max(1, b.getPageNum()) - 1;
            if (p >= pages.length) {
                continue;
            }
            if (pages[p].length() > 0) {
                pages[p].append('\n');
            }
            pages[p].append(nvl(b.getContent()));
        }
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) {
                all.append('\f');
            }
            all.append(pages[i]);
        }
        return all.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
