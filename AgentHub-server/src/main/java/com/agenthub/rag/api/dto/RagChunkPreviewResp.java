package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * 重新切片预览中的单条切片卡片。所属域：rag。
 * <p>
 * 字段与落库后的 RagChunkResp 对齐，便于用户直接比对「预览片」与「落库片」。
 */
@Data
public class RagChunkPreviewResp {

    /** 预览序号，从 0 起 */
    private Integer chunkIndex;
    /** 策略编码 */
    private String strategy;
    /** 策略中文标签：层级 / 条目 / 表格 / 叙述 */
    private String strategyLabel;
    /** 章节路径 */
    private String sectionPath;
    /** 超长再切序号，从 1 起；无则为空 */
    private Integer partIndex;
    /** Token 估算 */
    private Integer tokenEst;
    /** 与落库一致的完整正文 */
    private String content;
    /** 在解析文本中的起始偏移 */
    private Integer charStart;
    /** 在解析文本中的结束偏移 */
    private Integer charEnd;
}
