package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 补同步定时任务：只做「开关判定 → 调 runOnce」，其余行为全部由 runOnce 决定。
 * <p>
 * 覆盖：开关默认关（不查库不出站）、开关开则调一次、未就绪/已在跑不影响调用、
 * 异常不冒出定时线程、默认间隔 30 分钟。
 * reconciler 与 properties 全部 mock，不产生任何真实出站与 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorReconcileJobTest {

    @Mock
    private MemoryVectorReconciler reconciler;

    @Mock
    private MemoryVectorRebuildState rebuildState;

    private MemoryProperties properties;
    private MemoryVectorReconcileJob job;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        job = new MemoryVectorReconcileJob(reconciler, rebuildState, properties);
    }

    @Test
    void scheduleIsDisabledByDefault() {
        // 默认值即关：未显式配置时不得自动出站
        org.junit.jupiter.api.Assertions.assertFalse(properties.isVectorReconcileScheduleEnabled());

        job.reconcilePeriodically();

        verifyNoInteractions(reconciler);
    }

    @Test
    void disabledSwitchNeverTouchesReconciler() {
        properties.setVectorReconcileScheduleEnabled(false);

        job.reconcilePeriodically();

        // 关掉只是「不自动跑」，手动接口另走自己的调用，不经过本类
        verify(reconciler, never()).runOnce();
    }

    @Test
    void enabledRunsExactlyOneRound() {
        properties.setVectorReconcileScheduleEnabled(true);
        when(reconciler.runOnce())
                .thenReturn(new MemoryVectorReconciler.Summary("done", 3, 2, 1, 0));

        job.reconcilePeriodically();

        // 定时只触发一轮：单轮条数上限、按 Agent 推进都由 runOnce 内部负责
        verify(reconciler).runOnce();
    }

    @Test
    void notReadyRoundIsStillJustOneCall() {
        properties.setVectorReconcileScheduleEnabled(true);
        when(reconciler.runOnce())
                .thenReturn(new MemoryVectorReconciler.Summary("not_ready", 0, 0, 0, 0));

        job.reconcilePeriodically();

        verify(reconciler).runOnce();
    }

    @Test
    void alreadyRunningRoundIsStillJustOneCall() {
        properties.setVectorReconcileScheduleEnabled(true);
        when(reconciler.runOnce())
                .thenReturn(new MemoryVectorReconciler.Summary("already_running", 0, 0, 0, 0));

        job.reconcilePeriodically();

        verify(reconciler).runOnce();
    }

    @Test
    void runtimeExceptionDoesNotEscapeSchedulerThread() {
        properties.setVectorReconcileScheduleEnabled(true);
        when(reconciler.runOnce()).thenThrow(new IllegalStateException("db down"));

        // 定时线程抛异常会打断后续调度的观感，必须收敛成一行日志
        assertDoesNotThrow(() -> job.reconcilePeriodically());
    }

    @Test
    void defaultDelayIsThirtyMinutes() {
        // 默认 30 分钟：比过期清理（1h）密、比主对话双写慢得多
        org.junit.jupiter.api.Assertions.assertEquals(1_800_000L, properties.getVectorReconcileDelayMs());
    }
}
