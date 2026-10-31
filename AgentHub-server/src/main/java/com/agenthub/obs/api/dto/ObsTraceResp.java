package com.agenthub.obs.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次提问。所属域：obs。不返回编排预留列。空字段不输出。
 * 时间主字段为 startTime / endTime，禁止再以 startTime 作为主字段。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObsTraceResp {

    private String traceId;
    private Long sessionId;
    private Long agentId;
    private String agentName;
    private Long userId;
    private String channel;
    private String title;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer latencyMs;
    private String errorSummary;
    private Integer llmCallCount;
    private Integer toolCallCount;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private Integer tokenTotal;
    private String primaryModelName;
    private String finishReason;
}
