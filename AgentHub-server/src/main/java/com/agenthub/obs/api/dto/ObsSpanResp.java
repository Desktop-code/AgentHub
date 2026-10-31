package com.agenthub.obs.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 右栏的一步。所属域：obs。depth 按 parentId 算缩进，不入库。
 * 树键为 id + parentId；stepNo 优先展示；seq 仍为写入序。
 * 空字段不输出；正文主路径 inputText / outputText；content 仅兼容旧数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObsSpanResp {

    /** 行主键，树节点键 */
    private Long id;
    /** 父行 id；根为空 */
    private Long parentId;
    /** 展示步骤号 1 / 3.1 / 3.1.1 */
    private String stepNo;
    private Integer seq;
    private String spanType;
    private String name;
    private Long agentId;
    private String agentName;
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
    /** LLM：采样温度快照，前端 Tooltip 展示 */
    private BigDecimal temperature;
    /** LLM：请求 max_tokens 快照 */
    private Integer maxTokens;
    private Long toolId;
    private String toolName;
    private String toolType;
    private String toolCallId;
    private Integer retryCount;
    private Integer toolSuccess;
    private String routeDecision;
    private Long childAgentId;
    /** RETRIEVE：检索 query */
    private String retrieveQuery;
    /** RETRIEVE：命中条数 */
    private Integer retrieveHitCount;
    /** RETRIEVE：topK */
    private Integer retrieveTopK;
    /** RETRIEVE：知识库 id */
    private Long retrieveKbId;
    /** 扩展 JSON 原文；前端折叠面板高亮展示 */
    private String attrsJson;
    /** 人话摘要，来自 attrs_json.brief；无则不强行编 */
    private String brief;
    /** 本轮进入提示词的工具数。旧数据没有该字段时为空 */
    private Integer toolsInjected;
    /** 本轮进入提示词的 Skill 数 */
    private Integer skillsInjected;
    /** 系统提示、Skill、工具目录和 schema 的字符合计 */
    private Integer promptChars;
    /** 是否按预算截断或降级 */
    private Boolean truncated;
    /** 旧正文兼容；仅当 input/output 皆空时前端可回退展示 */
    private String content;
    private Integer depth;
}
