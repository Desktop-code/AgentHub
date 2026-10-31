package com.agenthub.obs.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 左栏会话。所属域：obs。agentName 为空时前端显示 #id。
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
    private String lastMsgPreview;
}
