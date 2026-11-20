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
    /** 切片策略：HIERARCHICAL / ENTRY_BASED / TABULAR / NARRATIVE */
    private String strategy;
    /** 章节路径：文章/节标题、条目名或表头 */
    private String sectionPath;
    /** 在解析文本中的起始偏移（含），空表示未建立映射 */
    private Integer charStart;
    /** 在解析文本中的结束偏移（不含） */
    private Integer charEnd;
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
