package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑步骤。所属域：runtime。对应 agent_run_event。
 * summary 是短摘要；contentText 是展示正文。两者都禁止密钥、Token 和工具实参。
 */
@Data
public class RunEvent {

    private Long id;
    private Long sessionId;
    private Integer seq;
    private String eventType;
    private String summary;
    /** 助手 Markdown、推理片段或工具结果。可空 */
    private String contentText;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private LocalDateTime createTime;
}
