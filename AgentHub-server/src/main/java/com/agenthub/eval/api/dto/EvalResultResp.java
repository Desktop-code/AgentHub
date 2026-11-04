package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单 case 结果出参。所属域：eval。
 * <p>占位行的判定与产物字段为 null，属正常状态（尚未执行）；
 * 前端展示时应把 null 说成「未执行」，不要当失败。
 */
@Data
public class EvalResultResp {

    private Long id;
    private Long runId;
    private String caseKey;
    private Long caseId;
    private Integer sortOrder;
    /** PASS|FAIL|ERROR|SKIPPED；null = 尚未出判定 */
    private String verdict;
    /** 1 规则门禁通过 / 0 未通过 / null 未判定 */
    private Integer rulePassed;
    /** OK|SKIPPED|ERROR|DISABLED */
    private String openjudgeStatus;
    private JsonNode scores;
    private JsonNode artifactSummary;
    private String sessionId;
    private String traceId;
    private Integer cacheHit;
    private JsonNode promptBreakdown;
    private Integer latencyMs;
    private JsonNode assertionDetails;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
}
