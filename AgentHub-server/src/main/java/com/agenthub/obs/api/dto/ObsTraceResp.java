package com.agenthub.obs.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次提问 / 一次编排运行。所属域：obs。空字段不输出。
 * 时间主字段为 startTime / endTime，禁止再以 startTime 作为主字段。
 * 编排列（workflowRunId / workflowId / workflowName）对话场景为空，前端按有无决定展示。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObsTraceResp {

    private String traceId;
    private Long sessionId;
    /** 编排场景：obs_trace.workflow_run_id（工作流 run 主键的字符串形式）；对话为空 */
    private String workflowRunId;
    /** 编排场景：工作流定义 id（从 attrs 解析，仅用于展示与回跳）；对话为空 */
    private Long workflowId;
    /** 编排场景：工作流名称（从 attrs 解析，无需再查 workflow 表）；对话为空 */
    private String workflowName;
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
    /**
     * trace 级扩展 JSON（已脱敏）。失败时含 cause 摘要，供面板第一行直接看原因；
     * 无内容时不输出（NON_NULL）。
     */
    private String attrsJson;
    /** 从 attrsJson 里解出的脱敏 cause 摘要；没有则为 null */
    private String cause;
}
