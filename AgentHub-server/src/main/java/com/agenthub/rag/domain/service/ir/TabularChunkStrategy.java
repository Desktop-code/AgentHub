package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表格型：按行切，保留表头。
 */
public class TabularChunkStrategy implements ChunkStrategy {

    @Override
    public DocumentType supportedType() {
        return DocumentType.TABULAR;
    }

    @Override
    public List<DocumentChunk> chunk(StructuredDocument doc) {
        List<DocumentChunk> out = new ArrayList<>();
        int rowIndex = 0;
        for (StructuredBlock block : doc.getBlocks()) {
            if (block.getBlockType() != BlockType.TABLE || !StringUtils.hasText(block.getContent())) {
                if (block.getBlockType() == BlockType.PARAGRAPH && DocumentStructurer.isTableLine(nvl(block.getContent()))) {
                    // 解析阶段有时把表格当段落，这里再按表行切开
                    out.addAll(splitTable(doc, block.getContent(), block.getPageNum(), rowIndex));
                    rowIndex += countDataRows(block.getContent());
                }
                continue;
            }
            List<DocumentChunk> part = splitTable(doc, block.getContent(), block.getPageNum(), rowIndex);
            out.addAll(part);
            rowIndex += countDataRows(block.getContent());
        }
        if (out.isEmpty()) {
            String joined = doc.getBlocks().stream()
                    .map(StructuredBlock::getContent)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n"));
            out.addAll(splitTable(doc, joined, 0, 0));
        }
        return out;
    }

    private List<DocumentChunk> splitTable(StructuredDocument doc, String table, int pageNum, int startRow) {
        String[] lines = table.split("\\n");
        List<String> data = new ArrayList<>();
        for (String line : lines) {
            if (StringUtils.hasText(line) && !isSeparator(line)) {
                data.add(line.trim());
            }
        }
        if (data.isEmpty()) {
            return List.of();
        }
        String header = data.get(0);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 1; i < data.size(); i++) {
            String row = data.get(i);
            String content = "表头：" + header + "\n" + row;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("file_name", doc.getFileName());
            meta.put("block_type", BlockType.TABLE.name());
            meta.put("source_type", DocumentType.TABULAR.name());
            meta.put("table_header", header);
            meta.put("row_index", startRow + i);
            chunks.add(new DocumentChunk(content, pageNum, header, meta));
        }
        if (chunks.isEmpty() && data.size() == 1) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("file_name", doc.getFileName());
            meta.put("block_type", BlockType.TABLE.name());
            meta.put("source_type", DocumentType.TABULAR.name());
            meta.put("table_header", header);
            chunks.add(new DocumentChunk(header, pageNum, header, meta));
        }
        return chunks;
    }

    private static int countDataRows(String table) {
        return (int) Arrays.stream(table.split("\\n"))
                .filter(s -> StringUtils.hasText(s) && !isSeparator(s))
                .count() - 1;
    }

    private static boolean isSeparator(String line) {
        String t = line.replace("|", "").replace("-", "").replace(":", "").replace(" ", "");
        return t.isEmpty();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
