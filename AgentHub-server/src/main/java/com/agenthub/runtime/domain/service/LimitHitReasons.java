package com.agenthub.runtime.domain.service;

/**
 * 撞限 finish_reason 常量。所属域：runtime。
 * 写入 session.error_summary / obs.finish_reason，不存配置本身。
 */
public final class LimitHitReasons {

    public static final String TIMEOUT = "timeout";
    public static final String MAX_ROUNDS = "max_rounds";
    public static final String MAX_TOOL_CALLS = "max_tool_calls";
    public static final String MAX_LLM_CALLS = "max_llm_calls";
    public static final String CONSECUTIVE_FAIL = "consecutive_fail_abort";
    public static final String ERROR = "error";

    private LimitHitReasons() {
    }
}
