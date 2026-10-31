package com.agenthub.rag.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库响应。所属域：rag。
 * 不含 Collection 名（内部实现细节）、不含模型 Key / Base URL；Embedding 只回模型实例 id 与展示名。
 */
@Data
public class RagKbResp {

    private Long id;
    private String name;
    private String description;
    private String storeType;
    /** DENSE_BM25 / DENSE_ONLY；建集失败时为 null */
    private String storeCapability;
    private Long embeddingModelId;
    /** Embedding 实例展示名，回填时模型已删则为 null */
    private String embeddingModelName;
    private Integer vectorDimension;
    private Long rerankModelId;
    /** Rerank 实例展示名，未配置为 null */
    private String rerankModelName;
    private String isolationMode;
    private String visibility;
    private String retrievalConfig;
    private String ingestConfig;
    /** READY / INIT_FAILED / DISABLED */
    private String status;
    /** 初始化失败原因；成功时为 null */
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
