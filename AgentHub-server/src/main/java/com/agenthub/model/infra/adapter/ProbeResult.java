package com.agenthub.model.infra.adapter;

/**
 * 探活结果。所属域：model。message 必须已经去掉 Key。
 *
 * @param success   是否探活成功
 * @param latencyMs 耗时
 * @param message   给人看的原因
 */
public record ProbeResult(boolean success, long latencyMs, String message) {

    /**
     * 用途未适配：不发起 HTTP，success=false，避免 HTTP 可达被当成模型可用。
     *
     * @param message 须含「用途未适配」与协议×用途
     * @return 失败结果，耗时 0
     */
    public static ProbeResult unsupported(String message) {
        return new ProbeResult(false, 0L, message);
    }
}
