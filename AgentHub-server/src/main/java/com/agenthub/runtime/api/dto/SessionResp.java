package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑会话。所属域：runtime。预览和错误摘要已脱敏。
 */
@Data
public class SessionResp {

    private Long id;
    private Long agentId;
    private String agentName;
    private String runMode;
    private Long userId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer messageCount;
    private String lastMsgPreview;
    private String errorSummary;
    /** 最近变更时间。列表上的相对时间用它，没有则退回 startTime */
    private LocalDateTime updateTime;
}
