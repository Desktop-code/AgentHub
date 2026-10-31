package com.agenthub.runtime.domain.service;

/**
 * 试跑执行失败。所属域：runtime。文案会进 error_summary，禁止携带密钥和堆栈。
 * finishReason 供 obs 写入，撞限时用 timeout / max_rounds 等明确值。
 */
public class TrialFailure extends RuntimeException {

    private final String finishReason;

    /**
     * @param message 已脱敏的失败说明
     */
    public TrialFailure(String message) {
        this(message, LimitHitReasons.ERROR);
    }

    /**
     * @param message      已脱敏的失败说明
     * @param finishReason obs.finish_reason，如 timeout、max_rounds
     */
    public TrialFailure(String message, String finishReason) {
        super(message);
        this.finishReason = finishReason == null || finishReason.isBlank()
                ? LimitHitReasons.ERROR : finishReason.trim();
    }

    /**
     * @return 写入观测的结束原因
     */
    public String getFinishReason() {
        return finishReason;
    }
}
