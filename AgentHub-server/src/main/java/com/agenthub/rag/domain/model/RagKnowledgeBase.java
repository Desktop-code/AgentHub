package com.agenthub.rag.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库。所属域：rag。对应 rag_knowledge_base。
 * 不含任何模型 Key / Base URL：Embedding 只存 model_instance 的 id，出站时再由模型中心解密。
 * collection_name 由系统生成、只在本域内部与 Milvus 交互，不对外暴露。
 */
@Data
public class RagKnowledgeBase {

    /** 主键 */
    private Long id;
    /** 租户；一期恒为 0 */
    private Long tenantId;
    /** 名称，同名未删除范围唯一 */
    private String name;
    /** 描述，可空 */
    private String description;
    /** 存储类型，一期 LOCAL_MILVUS */
    private String storeType;
    /** 系统生成的 Collection 名；创建失败时也可能为空 */
    private String collectionName;
    /** 集合实际能力 DENSE_BM25 / DENSE_ONLY；建集后回填 */
    private String storeCapability;
    /** model_instance.id，用途必须 EMBEDDING */
    private Long embeddingModelId;
    /** 建库时锁定的向量维度 */
    private Integer vectorDimension;
    /** 可选 rerank 模型实例 id */
    private Long rerankModelId;
    /** PHYSICAL / LOGICAL / PUBLIC */
    private String isolationMode;
    /** PUBLIC / RESTRICTED / PRIVATE */
    private String visibility;
    /** 默认检索参数 JSON（topK / rrf k / 改写与 Rerank 开关） */
    private String retrievalConfig;
    /** 入库参数 JSON（chunk_size / overlap）；② 使用 */
    private String ingestConfig;
    /** READY / INIT_FAILED / DISABLED */
    private String status;
    /** 初始化失败原因；成功时清空 */
    private String errorMsg;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
