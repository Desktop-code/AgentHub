package com.agenthub.obs.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * obs_span。所属域：obs 的 infra。
 * 树键 id + parent_id；step_no 在 seq 前。没有 deleted 列，不要加逻辑删除。
 * 正文主路径 input_text / output_text。已无 span_id / parent_span_id。
 */
@Data
@TableName("obs_span")
public class ObsSpanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Long parentId;
    private String stepNo;
    private Integer seq;
    private String spanType;
    private String name;
    private Long agentId;
    private String status;
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
    /** 旧正文兼容列；新埋点可空 */
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
