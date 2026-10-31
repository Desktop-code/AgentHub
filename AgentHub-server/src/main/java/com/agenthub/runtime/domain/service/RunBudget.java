package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EffectiveLimits;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次 ReAct 执行的计数与撞限检查。所属域：runtime。
 * 线程安全；工具与 LLM 回调可能跨线程。
 */
public final class RunBudget {

    private final EffectiveLimits limits;
    private final long deadlineNanos;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger consecutiveFails = new AtomicInteger();
    private volatile boolean maxRoundsHit;

    /**
     * @param limits 生效限制
     */
    public RunBudget(EffectiveLimits limits) {
        this.limits = limits;
        this.deadlineNanos = System.nanoTime() + Math.max(limits.timeoutMs(), 1000L) * 1_000_000L;
    }

    /**
     * @return 生效限制
     */
    public EffectiveLimits limits() {
        return limits;
    }

    /**
     * @return 墙钟截止（nanoTime）
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * @return 距截止剩余毫秒，至少 1
     */
    public long remainingMs() {
        long left = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(1L, left);
    }

    /**
     * 墙钟已到则抛出。
     */
    public void assertNotTimedOut() {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TrialFailure("撞限：timeout（整次执行超时）", LimitHitReasons.TIMEOUT);
        }
    }

    /**
     * LLM 调用前占用一次配额。
     */
    public void beforeLlm() {
        assertNotTimedOut();
        if (llmCalls.incrementAndGet() > limits.maxLlmCalls()) {
            throw new TrialFailure("撞限：max_llm_calls（超过 " + limits.maxLlmCalls() + " 次）",
                    LimitHitReasons.MAX_LLM_CALLS);
        }
    }

    /**
     * 工具调用前占用一次配额。
     */
    public void beforeTool() {
        assertNotTimedOut();
        if (toolCalls.incrementAndGet() > limits.maxToolCalls()) {
            throw new TrialFailure("撞限：max_tool_calls（超过 " + limits.maxToolCalls() + " 次）",
                    LimitHitReasons.MAX_TOOL_CALLS);
        }
    }

    /**
     * @param success 本轮工具是否成功
     */
    public void afterTool(boolean success) {
        if (success) {
            consecutiveFails.set(0);
            return;
        }
        if (consecutiveFails.incrementAndGet() >= limits.consecutiveFailAbort()) {
            throw new TrialFailure(
                    "撞限：consecutive_fail_abort（连续失败 " + limits.consecutiveFailAbort() + " 次）",
                    LimitHitReasons.CONSECUTIVE_FAIL);
        }
    }

    /**
     * 引擎发出超轮事件时标记；收尾再转成终态失败。
     */
    public void markMaxRounds() {
        maxRoundsHit = true;
    }

    /**
     * @return 是否已撞 max_rounds
     */
    public boolean isMaxRoundsHit() {
        return maxRoundsHit;
    }

    /**
     * 若已撞轮次上限则抛出。
     */
    public void assertRoundsOk() {
        if (maxRoundsHit) {
            throw new TrialFailure("撞限：max_rounds（超过 " + limits.maxRounds() + " 轮）",
                    LimitHitReasons.MAX_ROUNDS);
        }
    }
}
