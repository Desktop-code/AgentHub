package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 坏案列表行。所属域：eval。
 * <p>在 {@link EvalResultResp} 基础上补 suite/run 上下文，便于控制台回流。
 */
@Data
public class EvalBadCaseResp {

    private Long resultId;
    private Long runId;
    private Long suiteId;
    private Integer suiteVersion;
    private Long agentId;
    private String caseKey;
    private String verdict;
    private Integer rulePassed;
    private String openjudgeStatus;
    private JsonNode scores;
    private JsonNode assertionDetails;
    private JsonNode artifactSummary;
    private String sessionId;
    private String traceId;
    private Integer latencyMs;
    private String errorMessage;
    private LocalDateTime finishedTime;
    private LocalDateTime createTime;
}
