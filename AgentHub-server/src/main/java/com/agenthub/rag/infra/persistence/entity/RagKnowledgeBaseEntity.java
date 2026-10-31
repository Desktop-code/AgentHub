package com.agenthub.rag.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * rag_knowledge_base。所属域：rag 的 infra。列名与 sql/schema-delta-rag.sql 一致。
 * 本表不存任何模型 Key / Base URL，只存 model_instance 的 id。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_knowledge_base")
public class RagKnowledgeBaseEntity extends RagAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private String storeType;
    private String collectionName;
    private String storeCapability;
    private Long embeddingModelId;
    private Integer vectorDimension;
    private Long rerankModelId;
    private String isolationMode;
    private String visibility;
    /** JSON 列，读写按字符串处理，序列化交给应用层 */
    private String retrievalConfig;
    /** JSON 列，② 期放 chunk_size / overlap */
    private String ingestConfig;
    private String status;
    private String errorMsg;

    @TableLogic
    private Integer deleted;
}
