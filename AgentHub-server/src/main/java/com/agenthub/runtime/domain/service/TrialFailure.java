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
        this(message, LimitHitReasons.ERROR, null);
    }

    /**
     * @param message      已脱敏的失败说明
     * @param finishReason obs.finish_reason，如 timeout、max_rounds
     */
    public TrialFailure(String message, String finishReason) {
        this(message, finishReason, null);
    }

    /**
     * 带上原始异常，供日志打全栈与追踪写脱敏 cause。
     *
     * <p>为什么需要它：多数场景先把上游异常转成人话再抛，若不带 cause，
     * 上层 {@code log.warn(..., ex)} 就只能打到一句业务文案，
     * 「连通测成功、试跑失败」这类问题会看不出真正原因。
     *
     * @param message      已脱敏的失败说明，会进 error_summary
     * @param finishReason obs.finish_reason，如 timeout、max_rounds；空则按 error
     * @param cause        原始异常，可空；<b>只用于日志与追踪</b>，不写用户可见文案
     */
    public TrialFailure(String message, String finishReason, Throwable cause) {
        super(message, cause);
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
