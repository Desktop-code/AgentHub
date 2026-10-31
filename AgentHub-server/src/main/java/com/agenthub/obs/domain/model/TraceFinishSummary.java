package com.agenthub.obs.domain.model;

import lombok.Data;

/**
 * 结束 trace 时回写的汇总列。所属域：obs。
 * token 仅展示，不计价、不写金额。
 */
@Data
public class TraceFinishSummary {

    private Integer roundCount;
    private Integer llmCallCount;
    private Integer toolCallCount;
    private Integer retrieveCallCount;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private Integer tokenTotal;
    private Long primaryModelInstanceId;
    private String primaryModelName;
    /** completed / cancelled / error / timeout 等 */
    private String finishReason;
    private String attrsJson;
}
