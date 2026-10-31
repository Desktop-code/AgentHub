package com.agenthub.runtime.domain.model;

/**
 * 一次执行真正生效的运行限制。所属域：runtime。
 * 由平台 YAML 默认/硬顶与 Agent 可空覆盖解析得到，不入库。
 */
public record EffectiveLimits(
        long timeoutMs,
        int maxRounds,
        long toolTimeoutMs,
        int toolMaxRetries,
        int maxToolCalls,
        long llmTimeoutMs,
        int maxLlmCalls,
        int consecutiveFailAbort,
        int toolResultMaxChars
) {

    /**
     * 墙钟收紧为更小值（Router 父超时剩余）。
     *
     * @param ms 新的墙钟上限，至少 1000
     * @return 新实例
     */
    public EffectiveLimits withTimeoutMs(long ms) {
        long safe = Math.max(1000L, ms);
        if (safe == timeoutMs) {
            return this;
        }
        return new EffectiveLimits(safe, maxRounds, toolTimeoutMs, toolMaxRetries, maxToolCalls,
                llmTimeoutMs, maxLlmCalls, consecutiveFailAbort, toolResultMaxChars);
    }
}
