package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建知识库请求。所属域：rag。
 * 向量维度优先取模型实例连通测试返回的 embedding_dim，其次 extra_json.dimension，
 * 最后才用本请求的 vectorDimension 兜底。
 * Key / Base URL 一律不传：Embedding 只引用模型实例 id。
 */
@Data
public class RagKbCreateReq {

    /** 名称，未删除范围唯一 */
    @Size(max = 100, message = "最长 100")
    private String name;

    /** 描述 */
    @Size(max = 512, message = "最长 512")
    private String description;

    /** 存储类型，一期只接受 LOCAL_MILVUS；空按 LOCAL_MILVUS */
    @Size(max = 20, message = "最长 20")
    private String storeType;

    /** Embedding 模型实例 id，用途必须 EMBEDDING */
    private Long embeddingModelId;

    /** 维度兜底值；模型连通测试快照 / extraJson.dimension 优先 */
    private Integer vectorDimension;

    /** 可选 Rerank 模型实例 id，用途必须 RERANK */
    private Long rerankModelId;

    /** PHYSICAL / PUBLIC；空按 PHYSICAL。LOGICAL 一期拒绝 */
    @Size(max = 20, message = "最长 20")
    private String isolationMode;

    /** PUBLIC / PRIVATE；空按 PRIVATE。RESTRICTED 一期拒绝 */
    @Size(max = 20, message = "最长 20")
    private String visibility;

    /** 检索参数 JSON；空用平台默认 */
    @Size(max = 4000, message = "最长 4000")
    private String retrievalConfig;

    /** 入库参数 JSON；空用平台默认 */
    @Size(max = 4000, message = "最长 4000")
    private String ingestConfig;
}
