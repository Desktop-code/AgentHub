package com.agenthub.obs.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 左栏会话。所属域：obs。agentName 为空时前端显示 #id。
 * sessionTitle 是会话标题（自动生成或手改），为空时前端退回 lastMsgPreview。
 */
@Data
public class ObsSessionResp {

    private Long id;
    private Long agentId;
    private String agentName;
    private String runMode;
    private Long userId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String sessionTitle;
    private String lastMsgPreview;
}
