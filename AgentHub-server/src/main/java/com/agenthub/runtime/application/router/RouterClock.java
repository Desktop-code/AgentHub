package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.TrialFailure;

/**
 * 编排墙钟的硬拦截。所属域：runtime。
 *
 * <p>所有模板在「进入下一段执行前」都要看一眼父墙钟；文案与限流原因码是前端可直接展示的契约，
 * 抽到一处是为了保证六套模板一致，不允许各写一套措辞。
 */
public final class RouterClock {

    private RouterClock() {
    }

    /**
     * 撞到父墙钟即抛 {@link TrialFailure}，由上层统一按 FAIL 收尾（不降级、不伪装成功）。
     *
     * @param deadlineNanos 父墙钟截止（{@link System#nanoTime()} 口径）
     * @throws TrialFailure 已超时，finishReason=TIMEOUT
     */
    public static void assertBefore(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TrialFailure("撞限：timeout（Router 整次墙钟超时）", LimitHitReasons.TIMEOUT);
        }
    }
}
