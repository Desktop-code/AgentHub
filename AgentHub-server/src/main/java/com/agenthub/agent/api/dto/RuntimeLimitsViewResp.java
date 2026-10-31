package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 平台运行限制默认与硬顶。所属域：agent。给配置页占位和前端校验。
 */
@Data
public class RuntimeLimitsViewResp {

    private LimitBoundView timeoutMs;
    private LimitBoundView maxRounds;
    private LimitBoundView toolTimeoutMs;
    private LimitBoundView toolMaxRetries;
    private LimitBoundView maxToolCalls;
    private LimitBoundView llmTimeoutMs;
    private LimitBoundView maxLlmCalls;
    private LimitBoundView consecutiveFailAbort;
    private LimitBoundView toolResultMaxChars;

    /**
     * 单项默认 + 硬顶。
     */
    @Data
    public static class LimitBoundView {
        private long defaultValue;
        private long max;
    }
}
