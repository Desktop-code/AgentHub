package com.agenthub.rag.domain.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条知识切片：正文 + 可选页码 / 章节标题 + IR 元数据。所属域：rag。
 * <p>
 * 由结构感知切分产出；入库时 content 写入 rag_chunk，元数据用于 partIndex / 标题前置。
 */
public class DocumentChunk {

    private final String content;
    private final int pageNum;
    private final String sectionTitle;
    private final Map<String, Object> metadata;

    /**
     * @param content      切片正文（章节标题已前置）
     * @param pageNum      PDF 页码，从 1 起；非 PDF 为 0
     * @param sectionTitle 章节标题，可空
     */
    public DocumentChunk(String content, int pageNum, String sectionTitle) {
        this(content, pageNum, sectionTitle, null);
    }

    public DocumentChunk(String content, int pageNum, String sectionTitle, Map<String, Object> metadata) {
        this.content = content == null ? "" : content;
        this.pageNum = Math.max(0, pageNum);
        this.sectionTitle = sectionTitle == null ? "" : sectionTitle;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public DocumentChunk withMeta(String key, Object value) {
        if (key != null && value != null) {
            this.metadata.put(key, value);
        }
        return this;
    }

    public String metaString(String key) {
        Object v = metadata.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** @return 切片正文 */
    public String getContent() {
        return content;
    }

    /** @return PDF 页码，非 PDF 为 0 */
    public int getPageNum() {
        return pageNum;
    }

    /** @return 章节标题 */
    public String getSectionTitle() {
        return sectionTitle;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
