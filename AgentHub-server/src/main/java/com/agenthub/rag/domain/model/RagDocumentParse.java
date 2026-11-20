package com.agenthub.rag.domain.model;

/**
 * 文档解析文本产物。所属域：rag。对应 rag_document_parse。
 * <p>
 * 保存本轮入库实际使用的解析文本，供文档页原文对照与切片偏移映射；
 * 一篇文档仅一份，重灌 / 重切时整体覆盖。
 */
public class RagDocumentParse {

    /** 主键 */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** 知识库 */
    private Long kbId;
    /** 文档 */
    private Long docId;
    /** 解析文本（换行已统一为 \n，偏移以此为准） */
    private String content;
    /** 字符数 */
    private Integer charCount;
    /** 结构识别出的标题数 */
    private Integer headerCount;
    /** IR 原型 */
    private String docType;
    /** 实际路由到的切片策略 */
    private String strategy;
    /** 入库轮次 */
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer getHeaderCount() {
        return headerCount;
    }

    public void setHeaderCount(Integer headerCount) {
        this.headerCount = headerCount;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
