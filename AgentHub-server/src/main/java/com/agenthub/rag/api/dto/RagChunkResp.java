package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * 切片响应。所属域：rag。
 * <p>
 * 只暴露管理端核对切片需要的最小字段：序号、Token 估算与完整正文。
 * 不返回向量、不返回 Milvus 主键与租户/归属字段——这些属于内部实现细节。
 */
@Data
public class RagChunkResp {

    /** 切片主键，前端可用于定位 */
    private Long id;
    /** 文档内序号，从 0 起；前端展示为「切片 #n」时 +1 */
    private Integer chunkIndex;
    /** Token 估算值，仅用于展示 */
    private Integer tokenEst;
    /** 完整正文（rag_chunk.content，非 Milvus 截断副本） */
    private String content;
}
