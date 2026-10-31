package com.agenthub.rag.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * rag_chunk。所属域：rag 的 infra。
 * id 同时是 Milvus 主键，因此插入后必须回填 id 再写向量库；deleted 是业务软删标记，**不接 MyBatis-Plus 逻辑删除**
 * （否则查询默认过滤会让「按 docId 清理」查不到行）。
 * 过期列：expire_time（epoch 秒，0=永不过期）；与 Milvus 标量名一致。
 */
@Data
@TableName("rag_chunk")
public class RagChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private Long docId;
    private Integer chunkIndex;
    private Long parentChunkId;
    private Integer partIndex;
    private String content;
    private Integer tokenEst;
    private Long milvusPk;
    private Integer version;
    private String contentHash;
    private Integer deleted;
    private Long effectiveFrom;
    /** 过期 epoch 秒；库列 expire_time */
    private Long expireTime;
    private Long createBy;
    private Long ownerUserId;
}
