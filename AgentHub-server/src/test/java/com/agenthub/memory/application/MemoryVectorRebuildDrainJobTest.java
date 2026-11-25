package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 重建自动续补：刷新 / 切页面不打断的关键，是「续补在服务端跑」而不是「页面点按钮」。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>没有重建标记 → 一次库都不查（不产生任何 SQL / 出站）；</li>
 *   <li>开关关闭 → 退化为旧行为，仍只靠手动接口；</li>
 *   <li>重建中且有剩余 → 自动跑一轮，重建标记保持；</li>
 *   <li>剩余清零 → 收口重建态（放开平台级变更锁）；</li>
 *   <li>零进展 → 退避，不会每个 tick 都重炸出站；</li>
 *   <li>异常 / 池满被拒 → 不冒出调度线程，并进入退避；</li>
 *   <li>调度线程只投递、不同步出站（用「内联执行器」模拟池线程）。</li>
 * </ul>
 * 全部 mock：不打库、不连 Milvus、不打模型。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorRebuildDrainJobTest {

    @Mock
    private MemoryVectorReconciler reconciler;
    @Mock
    private MemoryVectorRebuildState rebuildState;
    @Mock
    private ThreadPoolTaskExecutor memoryTaskExecutor;

    private MemoryProperties properties;
    private MemoryVectorRebuildDrainJob job;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        job = new MemoryVectorRebuildDrainJob(reconciler, rebuildState, properties, memoryTaskExecutor);
        // 内联执行器：把「投递到池线程」折叠成同步执行，便于断言一轮的副作用
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(memoryTaskExecutor).execute(any());
    }

    @Test
    @DisplayName("默认开：进程内持有重建未完成标记时自动续补")
    void drainEnabledByDefault() {
        assertTrue(properties.isVectorRebuildDrainEnabled());
        assertTrue(properties.getVectorRebuildDrainDelayMs() > 0);
        assertTrue(properties.getVectorRebuildDrainMaxBackoffMs() >= properties.getVectorRebuildDrainDelayMs());
    }

    @Test
    @DisplayName("空闲期：没有重建标记时不查库不出站也不投递")
    void idleDoesNotTouchRepository() {
        when(rebuildState.isDrainWanted()).thenReturn(false);

        job.drainDuringRebuild();

        verifyNoInteractions(reconciler);
        verifyNoInteractions(memoryTaskExecutor);
    }

    @Test
    @DisplayName("开关关闭：退化为旧行为，仍只靠手动接口")
    void disabledNeverRuns() {
        properties.setVectorRebuildDrainEnabled(false);

        job.drainDuringRebuild();

        verifyNoInteractions(reconciler);
        verifyNoInteractions(rebuildState);
        verifyNoInteractions(memoryTaskExecutor);
    }

    @Test
    @DisplayName("重建中且有剩余：自动跑一轮，重建标记继续保持")
    void rebuildInProgressRunsOneRoundAndKeepsLock() {
        when(rebuildState.isDrainWanted()).thenReturn(true);
        when(reconciler.isRunning()).thenReturn(false);
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 200, 200, 0, 0));
        when(reconciler.pendingCount()).thenReturn(300);
        when(rebuildState.clearIfDrained(300)).thenReturn(false);

        job.drainDuringRebuild();

        // 真正的一轮落在池线程上，调度线程只投递
        verify(memoryTaskExecutor).execute(any());
        verify(reconciler).runOnce();
        verify(rebuildState).clearIfDrained(300);
    }

    @Test
    @DisplayName("剩余清零：收口重建态，放开平台级变更锁")
    void drainedClearsRebuildLock() {
        when(rebuildState.isDrainWanted()).thenReturn(true);
        when(reconciler.isRunning()).thenReturn(false);
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 20, 20, 0, 0));
        when(reconciler.pendingCount()).thenReturn(0);
        when(rebuildState.clearIfDrained(0)).thenReturn(true);

        job.drainDuringRebuild();

        verify(rebuildState).clearIfDrained(0);
    }

    @Test
    @DisplayName("零进展：退避，下一个 tick 不再立刻出站")
    void noProgressBacksOffBeforeNextRound() {
        when(rebuildState.isDrainWanted()).thenReturn(true);
        when(reconciler.isRunning()).thenReturn(false);
        // 未就绪：整轮跳过，pending 不变
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("not_ready", 0, 0, 0, 0));
        when(reconciler.pendingCount()).thenReturn(120);
        when(rebuildState.clearIfDrained(120)).thenReturn(false);

        job.drainDuringRebuild();
        // 立刻再来一次：仍在退避窗口内，不得再投递
        job.drainDuringRebuild();

        verify(reconciler, times(1)).runOnce();
        verify(memoryTaskExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("退避曲线：有进展回基础间隔，零进展指数翻倍并压到上限")
    void nextDelayCurve() {
        long base = 3_000L;
        long max = 30_000L;
        // 有进展：无论之前退避到多少，都回到基础间隔
        assertTrue(MemoryVectorRebuildDrainJob.nextDelayMs(base, max, 5, true) == base);
        // 第 1 轮零进展：不额外加倍，等一个基础间隔
        assertTrue(MemoryVectorRebuildDrainJob.nextDelayMs(base, max, 1, false) == base);
        // 第 2 轮：翻倍
        assertTrue(MemoryVectorRebuildDrainJob.nextDelayMs(base, max, 2, false) == 2 * base);
        // 第 3 轮：继续翻倍
        assertTrue(MemoryVectorRebuildDrainJob.nextDelayMs(base, max, 3, false) == 4 * base);
        // 任意大轮数：压到上限，不会无限增长
        assertTrue(MemoryVectorRebuildDrainJob.nextDelayMs(base, max, 16, false) == max);
    }

    @Test
    @DisplayName("另一轮补同步在跑：不排队，下个 tick 再看")
    void concurrentRoundIsSkipped() {
        when(rebuildState.isDrainWanted()).thenReturn(true);
        when(reconciler.isRunning()).thenReturn(true);

        job.drainDuringRebuild();

        verify(reconciler, never()).runOnce();
        verify(memoryTaskExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("单轮异常：不冒出调度线程，并进入退避")
    void exceptionIsSwallowedAndBackedOff() {
        when(rebuildState.isDrainWanted()).thenReturn(true);
        when(reconciler.isRunning()).thenReturn(false);
        when(reconciler.runOnce()).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> job.drainDuringRebuild());
        // 异常后同样退避：不立刻重炸
        job.drainDuringRebuild();
        verify(reconciler, times(1)).runOnce();
    }

    @Test
    @DisplayName("池满被拒：本轮丢掉并退避，绝不退回调度线程同步跑")
    void rejectedSubmissionBacksOffWithoutRunning() {
        when(rebuildState.isDrainWanted()).thenReturn(true);
        when(reconciler.isRunning()).thenReturn(false);
        doThrow(new TaskRejectedException("pool full")).when(memoryTaskExecutor).execute(any());

        // 投递失败只记日志，绝不因池满把出站塞回调度线程
        assertDoesNotThrow(() -> job.drainDuringRebuild());
        verify(reconciler, never()).runOnce();
        // 已经退避：下个 tick 不重试
        job.drainDuringRebuild();
        verify(memoryTaskExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("isDraining 只在开关开且存在续补请求时为真")
    void drainingReflectsSwitchAndState() {
        when(rebuildState.isDrainWanted()).thenReturn(false);
        assertFalse(job.isDraining());

        when(rebuildState.isDrainWanted()).thenReturn(true);
        assertTrue(job.isDraining());

        properties.setVectorRebuildDrainEnabled(false);
        assertFalse(job.isDraining());
    }
}
