package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EffectiveLimits;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次 ReAct 执行的计数与撞限检查。所属域：runtime。
 *
 * <p><b>作用域（本刀收口）</b>：一个实例只服务<b>一次</b> ReAct 执行，也就是<b>一个专家</b>
 * （入口专家 / 方案里的每个子专家各自新建）。连续失败计数因此天然按专家分开：
 * 「制度知识专家」把自己搜到熔断，不该连坐后面「报告撰写专家」的读文件与写稿。
 * 为把这层语义写成可测的契约，计数还带「归属」：{@link #afterTool(String, boolean)}
 * 发现归属变化时清零，杜绝以后有人复用同一个 budget 跑多个专家时把计数串起来。
 * <p>
 * 线程安全；工具与 LLM 回调可能跨线程。
 */
public final class RunBudget {

    private final EffectiveLimits limits;
    private final long deadlineNanos;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger consecutiveFails = new AtomicInteger();
    /** 连续失败计数当前归属的专家（空串＝未标注）；切换即清零，熔断不跨专家累积 */
    private final AtomicReference<String> failOwner = new AtomicReference<>("");
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
        afterTool(failOwner.get(), success);
    }

    /**
     * 记一次工具结果，并把连续失败计数限定在「当前专家」这个作用域内。
     *
     * <p>归属变化（专家切换）时先清零再计数：即使调用方出于某种原因复用了同一个
     * budget 跑多个专家，也不会把上一个专家的失败累加到下一个身上。
     * 熔断机制本身保留：同一专家连续失败到 {@code consecutive_fail_abort} 仍然打断它自己。
     *
     * @param owner   当前执行归属（通常是专家 id / 展示名）；空串表示不区分
     * @param success 本轮工具是否成功
     * @throws TrialFailure 当前专家连续失败达到阈值
     */
    public void afterTool(String owner, boolean success) {
        String scope = owner == null ? "" : owner.trim();
        String previous = failOwner.getAndSet(scope);
        // 专家切换：上一个专家的连续失败不算到这个专家头上
        if (!previous.equals(scope)) {
            consecutiveFails.set(0);
        }
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
