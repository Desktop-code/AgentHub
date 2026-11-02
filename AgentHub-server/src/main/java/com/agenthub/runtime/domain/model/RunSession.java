package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑会话。所属域：runtime。对应 agent_run_session，只保存元数据和短预览。
 * <p>标题与预览分列：session_title 是左栏主标题（自动生成或用户手改），
 * last_msg_preview 只放首轮提问的短预览，两者互不覆盖。
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
    /** 会话标题。NULL/空表示尚未生成，展示层退回 lastMsgPreview */
    private String sessionTitle;
    /** 标题来源：AUTO / MANUAL。MANUAL 不得被自动生成覆盖 */
    private String titleSource;
    private String lastMsgPreview;
    private String errorSummary;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
