package com.agenthub.spi.rag;

/**
 * 一个待写入向量的切片。所属域：spi。
 * 调用方：rag 的入库应用服务；实现方：RagProvider 把它转成 Milvus 行。
 * 字段与 {@code RagCollectionSchema} 的标量模板一一对应；content 传完整正文，实现方自行截断。
 */
public class ChunkVector {

    /** Milvus 主键，取 MySQL rag_chunk.id */
    private Long id;
    /** 知识库 */
    private Long kbId;
    /** 文档 */
    private Long docId;
    /** 切片 id，与 id 相同；单独保留便于阅读 */
    private Long chunkId;
    /** 租户 */
    private Long tenantId;
    /** 部门，可 0 */
    private Long deptId;
    /** 切片版本 */
    private Long version;
    /** 内容哈希 */
    private String contentHash;
    /** 0 正常 1 软删 */
    private Integer deleted;
    /** 生效时间 epoch 秒，0 立即 */
    private Long effectiveFrom;
    /** 过期时间 epoch 秒，0 不过期 */
    private Long expireTime;
    /** 上传人 */
    private Long createBy;
    /** 归属用户，可空 */
    private Long ownerUserId;
    /** 展示用标题 */
    private String title;
    /** 展示用来源 */
    private String source;
    /** 完整正文；实现方按集合 content 字段上限截断 */
    private String content;
    /** 向量，长度必须等于 KB.vector_dimension */
    private float[] embedding;

    /**
     * @return Milvus 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * @param id Milvus 主键
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * @return 知识库 id
     */
    public Long getKbId() {
        return kbId;
    }

    /**
     * @param kbId 知识库 id
     */
    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    /**
     * @return 文档 id
     */
    public Long getDocId() {
        return docId;
    }

    /**
     * @param docId 文档 id
     */
    public void setDocId(Long docId) {
        this.docId = docId;
    }

    /**
     * @return 切片 id
     */
    public Long getChunkId() {
        return chunkId;
    }

    /**
     * @param chunkId 切片 id
     */
    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    /**
     * @return 租户 id
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * @param tenantId 租户 id
     */
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * @return 部门 id
     */
    public Long getDeptId() {
        return deptId;
    }

    /**
     * @param deptId 部门 id
     */
    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    /**
     * @return 版本
     */
    public Long getVersion() {
        return version;
    }

    /**
     * @param version 版本
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * @return 内容哈希
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * @param contentHash 内容哈希
     */
    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    /**
     * @return 删除标记
     */
    public Integer getDeleted() {
        return deleted;
    }

    /**
     * @param deleted 删除标记
     */
    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    /**
     * @return 生效时间
     */
    public Long getEffectiveFrom() {
        return effectiveFrom;
    }

    /**
     * @param effectiveFrom 生效时间
     */
    public void setEffectiveFrom(Long effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    /**
     * @return 过期时间
     */
    public Long getExpireTime() {
        return expireTime;
    }

    /**
     * @param expireTime 过期时间
     */
    public void setExpireTime(Long expireTime) {
        this.expireTime = expireTime;
    }

    /**
     * @return 上传人
     */
    public Long getCreateBy() {
        return createBy;
    }

    /**
     * @param createBy 上传人
     */
    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    /**
     * @return 归属用户
     */
    public Long getOwnerUserId() {
        return ownerUserId;
    }

    /**
     * @param ownerUserId 归属用户
     */
    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    /**
     * @return 展示标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * @param title 展示标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * @return 来源
     */
    public String getSource() {
        return source;
    }

    /**
     * @param source 来源
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * @return 完整正文
     */
    public String getContent() {
        return content;
    }

    /**
     * @param content 完整正文
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * @return 向量
     */
    public float[] getEmbedding() {
        return embedding;
    }

    /**
     * @param embedding 向量
     */
    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
