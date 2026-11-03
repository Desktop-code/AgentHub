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
    /** 系统提示、Skill、工具目录、schema 与用户消息的字符合计 */
    private Integer promptChars;
    /** 上述合计的出站前估算 token（与 usage 精确值不同，用于看占比） */
    private Integer promptTokensEst;
    /** 受保护前缀字符数（L1 + 平台追加句 + 锁定段），红线，永不被裁 */
    private Integer protectedChars;
    /** 可压区字符数（开放段 + L3 追加 + 历史业务段） */
    private Integer softChars;
    /** 记忆段字符数；被预算让出时为 0 */
    private Integer memoryChars;
    /** Skill 段字符数（强制 + 按需） */
    private Integer skillChars;
    /** 工具目录字符数 */
    private Integer catalogChars;
    /** 可调用工具 schema 字符数（走 tools 字段） */
    private Integer schemaChars;
    /** 本轮用户侧消息字符数 */
    private Integer userChars;
    /** 其中【会话摘要】段字符数 */
    private Integer userSummaryChars;
    /** 其中【最近对话】段字符数 */
    private Integer userRecentChars;
    /** 其中【短期记忆】段字符数 */
    private Integer userShortChars;
    /** 其中本轮用户原文段字符数 */
    private Integer userCurrentChars;
    /** 本轮被落盘成文件的工具结果条数 */
    private Integer toolResultsOffloaded;
    /** 被落盘工具结果的原始正文合计字符数 */
    private Integer toolResultsOffloadedChars;
    /** 被硬上限截断正文的强制 Skill 条数 */
    private Integer forcedSkillClipped;
    /** 分段占比人话摘要（保护区/可压区/记忆/Skill/目录/schema/用户） */
    private String promptBreakdown;
    /** 是否按预算截断或降级 */
    private Boolean truncated;
    /** 旧正文兼容；仅当 input/output 皆空时前端可回退展示 */
    private String content;
    private Integer depth;
}
