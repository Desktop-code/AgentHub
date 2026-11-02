package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑会话。所属域：runtime。预览和错误摘要已脱敏。
 * sessionTitle 是左栏主标题（自动生成或手改），lastMsgPreview 是首问短预览，两者互不覆盖。
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
    /** 会话标题。空表示尚未生成，前端退回展示 lastMsgPreview */
    private String sessionTitle;
    /** AUTO 自动生成 / MANUAL 用户手改 */
    private String titleSource;
    private String lastMsgPreview;
    private String errorSummary;
    /** 最近变更时间。列表上的相对时间用它，没有则退回 startTime */
    private LocalDateTime updateTime;
}
