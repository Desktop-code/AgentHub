package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 重新切片请求。所属域：rag。
 * <p>
 * 只允许改片长与重叠：清洗、合并、噪声过滤等规则由 ChunkPipeline 固定，
 * 暴露过多参数会让同库文档的切片风格不可控，检索质量难以归因。
 */
@Data
public class RagRechunkReq {

    /** 目标片长（字符）。下限 100 避免切片碎成字节，上限 4000 与 IngestParams 的收紧范围一致 */
    @NotNull(message = "切片长度不能为空")
    @Min(value = 100, message = "切片长度至少 100")
    @Max(value = 4000, message = "切片长度最多 4000")
    private Integer chunkSize;

    /** 重叠字符数；不传则沿用知识库入库参数 */
    @Min(value = 0, message = "重叠不能为负")
    @Max(value = 2000, message = "重叠最多 2000")
    private Integer chunkOverlap;
}
