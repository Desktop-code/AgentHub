package com.agenthub.rag.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步入库任务。所属域：rag。对应 rag_ingest_task。
 * 进度与阶段落库，进程重启后仍可查到失败原因并重试；不依赖进程内内存。
 */
@Data
public class RagIngestTask {

    /** 主键 */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** 知识库 */
    private Long kbId;
    /** 文档 */
    private Long docId;
    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;
    /** 当前阶段：解析 / 切片 / 向量化 / 写库 */
    private String stage;
    /** 0-100 */
    private Integer progress;
    /** 已尝试次数，重试 +1 */
    private Integer attempts;
    /** 失败原因；成功时清空 */
    private String errorMsg;
    /** 本轮开始时间 */
    private LocalDateTime startTime;
    /** 本轮结束时间 */
    private LocalDateTime finishTime;
    /** 触发人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
