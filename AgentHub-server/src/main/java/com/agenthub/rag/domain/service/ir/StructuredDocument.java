package com.agenthub.rag.domain.service.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一文档模型（Intermediate Representation）。
 * 解析后的文档统一用该模型表达，后续识别与切片不再关心原始文件类型。
 */
public class StructuredDocument {

    private String docId;
    private String fileName;
    private DocumentType docType;
    private List<StructuredBlock> blocks = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public DocumentType getDocType() {
        return docType;
    }

    public void setDocType(DocumentType docType) {
        this.docType = docType;
    }

    public List<StructuredBlock> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<StructuredBlock> blocks) {
        this.blocks = blocks == null ? new ArrayList<>() : blocks;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    public int chunkSize() {
        Object v = metadata.get("chunkSize");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 450;
    }

    public int chunkOverlap() {
        Object v = metadata.get("chunkOverlap");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 100;
    }

    public int minChunkSize() {
        Object v = metadata.get("minChunkSize");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 150;
    }
}
