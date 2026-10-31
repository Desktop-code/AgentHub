package com.agenthub.rag.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档响应。所属域：rag。
 * 不含原文件绝对路径（只回相对路径），也不含向量库内部字段。
 */
@Data
public class RagDocumentResp {

    private Long id;
    private Long kbId;
    private String title;
    /** 相对落盘路径，如 12/345.txt */
    private String sourceUri;
    private String mime;
    private Long byteSize;
    private Integer chunkCount;
    /** PENDING / RUNNING / READY / FAILED */
    private String status;
    /** 失败原因；成功时为 null */
    private String errorMsg;
    private Integer version;
    /** 入库任务状态，与 doc.status 同步推进 */
    private String taskStatus;
    /** 当前阶段：解析 / 切片 / 向量化 / 写入 */
    private String stage;
    /** 0-100 */
    private Integer progress;
    /** 已尝试次数，重试递增 */
    private Integer attempts;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
