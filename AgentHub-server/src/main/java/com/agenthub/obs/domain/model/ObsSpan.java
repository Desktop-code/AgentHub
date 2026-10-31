package com.agenthub.obs.domain.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次执行步骤。所属域：obs。不是表实体。
 * 树键为 id + parentId；stepNo 为展示号；seq 是同一条 trace 内的写入序。
 * 正文主路径为 inputText / outputText；content 仅兼容旧数据。
 */
@Data
public class ObsSpan {

    private Long id;
    private String traceId;
    /** 父行自增 id；根为空 */
    private Long parentId;
    /** 展示步骤号：串行 1/2/3，并行分叉 3.1/3.2 */
    private String stepNo;
    private Integer seq;
    private SpanType spanType;
    private String name;
    private Long agentId;
    private ObsStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer latencyMs;
    private String inputText;
    private String outputText;
    private String errorSummary;
    private Long modelInstanceId;
    private String modelName;
    private String provider;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private Integer tokenTotal;
    private String finishReason;
    private Long billingLedgerId;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Long toolId;
    private String toolName;
    private String toolType;
    private String toolCallId;
    private Integer retryCount;
    private Integer toolSuccess;
    private String routeDecision;
    private Long childAgentId;
    private String retrieveQuery;
    private Integer retrieveHitCount;
    private Integer retrieveTopK;
    private Long retrieveKbId;
    private String attrsJson;
    /** 旧正文兼容；新埋点可空 */
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
