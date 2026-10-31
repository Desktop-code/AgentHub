package com.agenthub.rag.domain.service.ir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IR 最小单元：标题、条目、字段、段落、表格或列表。
 */
public class StructuredBlock {

    private String blockId;
    private BlockType blockType;
    private String content;
    private int level;
    private String parentId;
    private int pageNum;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public BlockType getBlockType() {
        return blockType;
    }

    public void setBlockType(BlockType blockType) {
        this.blockType = blockType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    public void putMeta(String key, Object value) {
        if (key != null && value != null) {
            this.metadata.put(key, value);
        }
    }

    public String metaString(String key) {
        Object v = metadata.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
