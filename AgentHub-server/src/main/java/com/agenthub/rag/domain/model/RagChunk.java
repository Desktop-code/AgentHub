package com.agenthub.rag.domain.model;

import lombok.Data;

/**
 * 切片。所属域：rag。对应 rag_chunk；id 同时用作 Milvus 主键。
 * content 是完整原文（Milvus 只存截断副本），检索命中后以本表为准回填。
 */
@Data
public class RagChunk {

    /** 主键；同时是 Milvus 标量 chunk_id */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** 知识库 */
    private Long kbId;
    /** 文档 */
    private Long docId;
    /** 文档内序号，从 0 起 */
    private Integer chunkIndex;
    /** 父块（条目型父片或超长再切的原始片） */
    private Long parentChunkId;
    /** 超长切分序号，从 1 起 */
    private Integer partIndex;
    /** 完整正文 */
    private String content;
    /** 估算 token */
    private Integer tokenEst;
    /** Milvus 主键，通常等于 id */
    private Long milvusPk;
    /** 重嵌代次 */
    private Integer version;
    /** 内容哈希，去重/变更检测 */
    private String contentHash;
    /** 0 正常 1 软删 */
    private Integer deleted;
    /** 生效时间（epoch 秒），0 立即 */
    private Long effectiveFrom;
    /** 过期时间（epoch 秒），0 永不过期 */
    private Long expireTime;
    /** 上传人 */
    private Long createBy;
    /** 归属用户，可空 */
    private Long ownerUserId;
}
