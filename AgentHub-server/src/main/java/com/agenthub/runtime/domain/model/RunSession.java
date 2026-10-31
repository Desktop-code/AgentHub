package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑会话。所属域：runtime。对应 agent_run_session，只保存元数据和短预览。
 */
@Data
public class RunSession {

    private Long id;
    private Long agentId;
    private String runMode;
    private Long userId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer messageCount;
    private String lastMsgPreview;
    private String errorSummary;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
