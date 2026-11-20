package com.agenthub.rag.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 重新切片预览响应。所属域：rag。
 * <p>
 * 预览只读：按提交的片长/重叠对解析文本重算切片，返回预计片数与卡片，
 * 不写 rag_chunk、不动向量、不改变文档状态；确认覆盖仍走原 rechunk 接口。
 */
@Data
public class RagRechunkPreviewResp {

    /** 本次预览使用的片长 */
    private Integer chunkSize;
    /** 本次预览使用的重叠 */
    private Integer chunkOverlap;
    /** 预计切片数 */
    private Integer chunkCount;
    /** 预计 Token 合计 */
    private Integer tokenTotal;
    /** 预览卡片；超长文档只回前 {@code PREVIEW_LIMIT} 条，chunkCount 仍是全量 */
    private List<RagChunkPreviewResp> chunks;
    /** true 表示卡片被截断，仅展示前 N 条 */
    private Boolean truncated;
}
