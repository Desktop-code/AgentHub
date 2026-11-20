package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * 文档解析文本响应。所属域：rag。
 * <p>
 * 文档页左栏原文对照用。优先返回本轮入库实际使用的解析产物；
 * 老文档无产物时从原文件只读再解析，仅用于展示，不落库、不改切片与向量。
 */
@Data
public class RagParseTextResp {

    /** 解析文本（换行统一为 \n，与切片 charStart/charEnd 同一坐标系） */
    private String text;
    /** 字符数 */
    private Integer charCount;
    /** 结构识别出的标题数；实时解析且未做结构分析时为 0 */
    private Integer headerCount;
    /** true 表示来自原文件即时解析，不是入库产物 */
    private Boolean fromSource;
    /** 不可用原因；可用时为空 */
    private String message;
}
