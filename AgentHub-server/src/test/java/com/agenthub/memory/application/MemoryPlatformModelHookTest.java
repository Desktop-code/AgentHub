package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.model.domain.model.ModelCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryPlatformModelHook} 的窄单测。所属域：memory。
 * <p>
 * 全部 mock，不出站、不连 Milvus / MySQL；只验证分支契约：
 * <ul>
 *   <li>Embedding 变更：先失效缓存，再在池线程按快照 ensure，就绪后才跑<b>有界一轮</b>补同步；</li>
 *   <li>未就绪：不投递补同步（避免空转烧出站）；</li>
 *   <li>Rerank / 快照更新：只失效缓存，不 ensure、不补同步；</li>
 *   <li>池拒绝：不影响平台级设置结果。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemoryPlatformModelHookTest {

    @Mock
    private MemoryVectorModelSlotResolver slots;

    @Mock
    private MemoryVectorSupport support;

    @Mock
    private MemoryVectorReconciler reconciler;

    @Mock
    private MemoryVectorRebuildState rebuildState;

    @Mock
    private ThreadPoolTaskExecutor executor;

    /**
     * 让 mock 池同步执行任务，便于断言提交后的 ensure / 补同步。
     */
    private MemoryPlatformModelHook hookWithInlineExecutor() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any());
        return new MemoryPlatformModelHook(slots, support, reconciler, rebuildState, executor);
    }

    @Test
    @DisplayName("Embedding 变更：失效缓存 → ensure 就绪 → 有界一轮补同步")
    void embeddingChangeEnsuresAndRunsBoundedReconcile() {
        MemoryPlatformModelHook hook = hookWithInlineExecutor();
        when(support.refresh()).thenReturn(MemoryVectorReadiness.ready("agenthub_memory_long", 1024, 9L, 12L));
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 1, 1, 0, 0));

        hook.onPlatformChanged(ModelCapability.EMBEDDING, true);

        verify(slots).invalidate();
        verify(support).invalidate();
        verify(support).refresh();
        verify(reconciler).runOnce();
    }

    @Test
    @DisplayName("未就绪：ensure 后不投递补同步")
    void notReadySkipsReconcile() {
        MemoryPlatformModelHook hook = hookWithInlineExecutor();
        when(support.refresh()).thenReturn(MemoryVectorReadiness.notReady("agenthub_memory_long", 0, "缺少维度快照"));

        hook.onPlatformChanged(ModelCapability.EMBEDDING, true);

        verify(support).refresh();
        verify(reconciler, never()).runOnce();
    }

    @Test
    @DisplayName("取消平台级（resync=false）：ensure 但不补同步")
    void cancelLockRefreshesWithoutReconcile() {
        MemoryPlatformModelHook hook = hookWithInlineExecutor();
        when(support.refresh()).thenReturn(MemoryVectorReadiness.notReady("agenthub_memory_long", 0, "未设置平台级 Embedding"));

        hook.onPlatformChanged(ModelCapability.EMBEDDING, false);

        verify(support).refresh();
        verify(reconciler, never()).runOnce();
    }

    @Test
    @DisplayName("Rerank 变更：只失效缓存，不 ensure、不补同步")
    void rerankOnlyInvalidates() {
        MemoryPlatformModelHook hook = new MemoryPlatformModelHook(slots, support, reconciler, rebuildState, executor);

        hook.onPlatformChanged(ModelCapability.RERANK, false);

        verify(slots).invalidate();
        verify(support).invalidate();
        verifyNoInteractions(executor);
        verify(support, never()).refresh();
        verify(reconciler, never()).runOnce();
    }

    @Test
    @DisplayName("连通测试快照更新：只失效缓存，不 ensure、不补同步")
    void snapshotUpdateOnlyInvalidates() {
        MemoryPlatformModelHook hook = new MemoryPlatformModelHook(slots, support, reconciler, rebuildState, executor);

        hook.onSnapshotUpdated(ModelCapability.EMBEDDING);

        verify(slots).invalidate();
        verify(support).invalidate();
        verifyNoInteractions(executor);
        verify(support, never()).refresh();
        verify(reconciler, never()).runOnce();
    }

    @Test
    @DisplayName("线程池拒绝：吞掉拒绝异常，平台级设置仍视为成功")
    void rejectedPoolDoesNotThrow() {
        MemoryPlatformModelHook hook = new MemoryPlatformModelHook(slots, support, reconciler, rebuildState, executor);
        doAnswer(invocation -> {
            throw new org.springframework.core.task.TaskRejectedException("full");
        }).when(executor).execute(any());

        hook.onPlatformChanged(ModelCapability.EMBEDDING, true);

        // 拒绝发生在投递阶段，ensure 未执行；不抛出即满足「降级不堵调用方」
        verify(reconciler, never()).runOnce();
    }

    @Test
    @DisplayName("换绑后有剩余：交接给自动续补，页面刷新也不会断")
    void leftoverPendingRequestsAutoDrain() {
        MemoryPlatformModelHook hook = hookWithInlineExecutor();
        when(support.refresh()).thenReturn(MemoryVectorReadiness.ready("agenthub_memory_long", 1024, 9L, 12L));
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 200, 200, 0, 0));
        when(reconciler.pendingCount()).thenReturn(120);

        hook.onPlatformChanged(ModelCapability.EMBEDDING, true);

        // 有界一轮吃不下的部分必须交给自动续补，否则只能靠人工点「继续补同步」
        verify(rebuildState).requestDrain();
        verify(rebuildState, never()).clearIfDrained(120);
    }

    @Test
    @DisplayName("换绑后已补齐：撤掉续补请求，页头不残留「进行中」")
    void drainedChangeClearsDrainRequest() {
        MemoryPlatformModelHook hook = hookWithInlineExecutor();
        when(support.refresh()).thenReturn(MemoryVectorReadiness.ready("agenthub_memory_long", 1024, 9L, 12L));
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 5, 5, 0, 0));
        when(reconciler.pendingCount()).thenReturn(0);

        hook.onPlatformChanged(ModelCapability.EMBEDDING, true);

        verify(rebuildState).clearIfDrained(0);
        verify(rebuildState, never()).requestDrain();
    }

    @Test
    @DisplayName("同步中查询直接委托 Reconciler")
    void isSyncRunningDelegates() {
        MemoryPlatformModelHook hook = new MemoryPlatformModelHook(slots, support, reconciler, rebuildState, executor);
        when(reconciler.isRunning()).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertTrue(hook.isSyncRunning());
    }

    @Test
    @DisplayName("重建中查询直接委托重建状态机")
    void isRebuildingDelegates() {
        MemoryPlatformModelHook hook = new MemoryPlatformModelHook(slots, support, reconciler, rebuildState, executor);
        when(rebuildState.isRebuilding()).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertTrue(hook.isRebuilding());
    }
}
