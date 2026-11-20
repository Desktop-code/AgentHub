package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * 切片响应。所属域：rag。
 * <p>
 * 暴露管理端核对切片与原文对照所需字段：序号、策略、章节路径、Token、正文与解析文本偏移。
 * 不返回向量、不返回 Milvus 主键与租户/归属字段——这些属于内部实现细节。
 * 旧数据没有策略/路径/偏移时对应字段为空，前端不得据此编造。
 */
@Data
public class RagChunkResp {

    /** 切片主键，前端可用于定位 */
    private Long id;
    /** 文档内序号，从 0 起；前端展示为「切片 #n」时 +1 */
    private Integer chunkIndex;
    /** 父块 id；超长再切时便于追溯 */
    private Long parentChunkId;
    /** 超长切分序号，从 1 起 */
    private Integer partIndex;
    /** 策略编码：HIERARCHICAL / ENTRY_BASED / TABULAR / NARRATIVE */
    private String strategy;
    /** 策略中文标签：层级 / 条目 / 表格 / 叙述 */
    private String strategyLabel;
    /** 章节路径：文章/节标题、条目名或表头 */
    private String sectionPath;
    /** 在解析文本中的起始偏移（含）；空表示未建立映射 */
    private Integer charStart;
    /** 在解析文本中的结束偏移（不含） */
    private Integer charEnd;
    /** Token 估算值，仅用于展示 */
    private Integer tokenEst;
    /** 完整正文（rag_chunk.content，非 Milvus 截断副本） */
    private String content;
}
