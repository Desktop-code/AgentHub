package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条目型：按条目切父块，按字段切子块，注入 entry_name + field_type。
 */
public class EntryBasedChunkStrategy implements ChunkStrategy {

    @Override
    public DocumentType supportedType() {
        return DocumentType.ENTRY_BASED;
    }

    @Override
    public List<DocumentChunk> chunk(StructuredDocument doc) {
        List<DocumentChunk> out = new ArrayList<>();
        if (doc == null || doc.getBlocks().isEmpty()) {
            return out;
        }
        List<StructuredBlock> group = new ArrayList<>();
        for (StructuredBlock block : doc.getBlocks()) {
            if (block.getBlockType() == BlockType.ENTRY && !group.isEmpty()) {
                // 遇到下一条目先冲刷上一组，保证 FAQ/中药条目互不粘连
                out.addAll(flushGroup(doc, group));
                group.clear();
            }
            group.add(block);
        }
        if (!group.isEmpty()) {
            out.addAll(flushGroup(doc, group));
        }
        return out;
    }

    private List<DocumentChunk> flushGroup(StructuredDocument doc, List<StructuredBlock> group) {
        List<DocumentChunk> chunks = new ArrayList<>();
        StructuredBlock entry = group.stream().filter(b -> b.getBlockType() == BlockType.ENTRY).findFirst().orElse(null);
        String entryName = entry == null ? "" : firstText(entry.metaString("entry_name"), entry.getContent());
        String sourceRef = entry == null ? "" : nvl(entry.metaString("source_ref"));
        String parentId = entry == null ? group.get(0).getBlockId() : entry.getBlockId();
        int pageNum = entry == null ? 0 : entry.getPageNum();

        StringBuilder parentBody = new StringBuilder();
        if (entry != null && StringUtils.hasText(entry.getContent())) {
            parentBody.append(entry.getContent());
        }
        for (StructuredBlock block : group) {
            if (block.getBlockType() == BlockType.FIELD) {
                String fieldType = nvl(block.metaString("field_type"));
                String fieldBody = nvl(block.getContent());
                if (parentBody.length() > 0) {
                    parentBody.append('\n');
                }
                if (StringUtils.hasText(fieldType)) {
                    parentBody.append(fieldType).append("：").append(fieldBody);
                } else {
                    parentBody.append(fieldBody);
                }
                String content = buildFieldContent(entryName, fieldType, fieldBody);
                chunks.add(toChunk(doc, content, pageNum, StringUtils.hasText(fieldType) ? fieldType : entryName,
                        BlockType.FIELD, parentId, entryName, fieldType, sourceRef));
            } else if (block.getBlockType() == BlockType.PARAGRAPH || block.getBlockType() == BlockType.LIST) {
                if (parentBody.length() > 0) {
                    parentBody.append('\n');
                }
                parentBody.append(nvl(block.getContent()));
            }
        }
        if (chunks.isEmpty() && parentBody.length() > 0) {
            chunks.add(toChunk(doc, parentBody.toString(), pageNum, entryName,
                    BlockType.ENTRY, parentId, entryName, "", sourceRef));
            return chunks;
        }
        // 父块：整条条目，便于「这个中药全部信息」召回
        if (parentBody.length() > 0 && chunks.size() >= 1) {
            DocumentChunk parent = toChunk(doc, parentBody.toString(), pageNum, entryName,
                    BlockType.ENTRY, parentId, entryName, "", sourceRef);
            List<DocumentChunk> ordered = new ArrayList<>();
            ordered.add(parent);
            ordered.addAll(chunks);
            return ordered;
        }
        return chunks;
    }

    private static String buildFieldContent(String entryName, String fieldType, String body) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(entryName)) {
            sb.append(entryName);
        }
        if (StringUtils.hasText(fieldType)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(fieldType);
        }
        if (StringUtils.hasText(body)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(body);
        }
        return sb.toString();
    }

    private DocumentChunk toChunk(StructuredDocument doc, String content, int pageNum, String section,
                                  BlockType blockType, String parentId, String entryName,
                                  String fieldType, String sourceRef) {
        Map<String, Object> meta = baseMeta(doc, blockType, parentId);
        if (StringUtils.hasText(entryName)) {
            meta.put("entry_name", entryName);
        }
        if (StringUtils.hasText(fieldType)) {
            meta.put("field_type", fieldType);
        }
        if (StringUtils.hasText(sourceRef)) {
            meta.put("source_ref", sourceRef);
        }
        meta.put("source_type", DocumentType.ENTRY_BASED.name());
        return new DocumentChunk(content, pageNum, section, meta);
    }

    static Map<String, Object> baseMeta(StructuredDocument doc, BlockType blockType, String parentId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("file_name", doc.getFileName());
        meta.put("block_type", blockType == null ? "" : blockType.name());
        if (StringUtils.hasText(parentId)) {
            meta.put("parent_id", parentId);
        }
        return meta;
    }

    private static String firstText(String a, String b) {
        return StringUtils.hasText(a) ? a.trim() : (b == null ? "" : b.trim());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
