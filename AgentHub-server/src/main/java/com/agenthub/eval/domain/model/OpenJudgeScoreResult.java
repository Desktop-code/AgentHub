package com.agenthub.eval.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * OpenJudge 软评分出参。所属域：eval 的 domain。
 * <p>只描述评分通道结果，<b>不</b>决定 case verdict / gate_passed。
 */
@Data
public class OpenJudgeScoreResult {

    /** 状态：OK|SKIPPED|ERROR|DISABLED */
    private OpenJudgeStatus status = OpenJudgeStatus.DISABLED;
    /** 综合分，可空（SKIPPED/ERROR/DISABLED 时常空） */
    private Double score;
    /** 人话理由 / 失败摘要 */
    private String reason;
    /** 元数据（含逐 grader 明细），可空 */
    private JsonNode metadata;
    /** 原始响应 JSON 文本（已截断），可空 */
    private String rawJson;

    /**
     * @param status 状态
     * @param reason 说明
     * @return 结果
     */
    public static OpenJudgeScoreResult of(OpenJudgeStatus status, String reason) {
        OpenJudgeScoreResult one = new OpenJudgeScoreResult();
        one.setStatus(status);
        one.setReason(reason);
        return one;
    }
}
