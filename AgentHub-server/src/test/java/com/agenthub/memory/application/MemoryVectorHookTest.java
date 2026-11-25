package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 双写挂载点：只处理 LONG、默认提交后投递、停用/过期转删、池拒绝只日志，
 * 以及刀5 的状态位打标（PENDING / SYNCED / FAILED）。
 * executor / writer / 仓储全部 mock，任务体在测试线程里手动执行，不产生任何真实出站与 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorHookTest {

    @Mock
    private ThreadPoolTaskExecutor memoryTaskExecutor;
    @Mock
    private MemoryVectorWriter writer;
    @Mock
    private MemoryItemRepository items;

    private MemoryVectorHook hook;

    @BeforeEach
    void setUp() {
        hook = new MemoryVectorHook(memoryTaskExecutor, writer, items);
    }

    @AfterEach
    void tearDown() {
        // 用例可能开关同步上下文，避免污染同一 JVM 里后续测试
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void nonLongIsIgnored() {
        MemoryItem shortItem = item(11L, "LONG");
        shortItem.setScope("SHORT");

        hook.afterSaved(shortItem);

        verifyNoInteractions(memoryTaskExecutor, writer, items);
    }

    @Test
    void nullOrUnsavedItemIsIgnored() {
        hook.afterSaved(null);
        hook.afterSaved(item(null, "LONG"));

        verifyNoInteractions(memoryTaskExecutor, writer, items);
    }

    @Test
    void longSavedIsMarkedPendingAndDispatchedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        hook.afterSaved(item(11L, "LONG"));
        // 状态位随内容一起写：提交前必须已经落 PENDING，否则崩溃会漏同步
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.PENDING, 0, null);
        // 事务未提交：不得提前出站
        verifyNoInteractions(memoryTaskExecutor);

        // 手动触发 afterCommit，模拟事务提交回调
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(memoryTaskExecutor).execute(task.capture());

        when(writer.attempt(any(MemoryItem.class))).thenReturn(MemoryVectorWriter.Attempt.synced());
        task.getValue().run();
        verify(writer).attempt(any(MemoryItem.class));
        verify(writer, never()).remove(any());
        // 写入成功 → 落 SYNCED 并清零失败次数
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.SYNCED, 0, null);
    }

    @Test
    void writeFailureMarksFailedWithIncrementedCount() {
        MemoryItem source = item(11L, "LONG");
        source.setVectorSyncFailCount(1);
        when(writer.attempt(any(MemoryItem.class)))
                .thenReturn(MemoryVectorWriter.Attempt.failed("MilvusTimeoutException: timeout"));

        hook.afterSaved(source);
        runDispatchedTask();

        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.FAILED, 2,
                "MilvusTimeoutException: timeout");
    }

    @Test
    void notReadyKeepsPendingWithoutMarking() {
        when(writer.attempt(any(MemoryItem.class)))
                .thenReturn(MemoryVectorWriter.Attempt.deferred("记忆向量未就绪"));

        hook.afterSaved(item(11L, "LONG"));
        runDispatchedTask();

        // 未就绪期间只保留 PENDING，不得误标 FAILED 烧掉失败次数
        verify(items, never()).markVectorSync(any(), eq(MemoryVectorSyncStatus.FAILED), anyInt(), anyString());
        verify(items, never()).markVectorSync(any(), eq(MemoryVectorSyncStatus.SYNCED), anyInt(), anyString());
    }

    @Test
    void disabledLongRemovesVectorAndMarksTerminal() {
        MemoryItem disabled = item(11L, "LONG");
        disabled.setStatus(0);
        when(writer.removeAttempt(List.of(11L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        hook.afterSaved(disabled);
        runDispatchedTask();

        verify(writer).removeAttempt(List.of(11L));
        verify(writer, never()).attempt(any());
        // 不该有向量的行落终态，避免补同步每轮白扫
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.SYNCED, 0, null);
    }

    @Test
    void expiredLongRemovesVector() {
        MemoryItem expired = item(11L, "LONG");
        expired.setExpiredTime(LocalDateTime.now().minusMinutes(1));
        when(writer.removeAttempt(List.of(11L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        hook.afterSaved(expired);
        runDispatchedTask();

        verify(writer).removeAttempt(List.of(11L));
        verify(writer, never()).attempt(any());
    }

    @Test
    void blankContentRemovesVector() {
        MemoryItem blank = item(11L, "LONG");
        blank.setContent("   ");
        when(writer.removeAttempt(List.of(11L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        hook.afterSaved(blank);
        runDispatchedTask();

        verify(writer).removeAttempt(List.of(11L));
        verify(writer, never()).attempt(any());
    }

    @Test
    void removeFailedMarksFailedWithIncrementedCount() {
        when(writer.removeAttempt(List.of(11L)))
                .thenReturn(MemoryVectorWriter.Attempt.failed("MilvusTimeoutException: timeout"));

        hook.afterSaved(expiredItem(11L));
        runDispatchedTask();

        // 刀8：联删失败不能静默当成功，必须留 FAILED + 自增次数，交给补同步收口
        verify(items).bumpVectorSyncFail(List.of(11L), MemoryVectorSyncStatus.FAILED,
                "MilvusTimeoutException: timeout");
        verify(items, never()).markVectorSync(any(), eq(MemoryVectorSyncStatus.SYNCED), anyInt(), any());
    }

    @Test
    void removeDeferredKeepsPendingWithoutBurningFailCount() {
        when(writer.removeAttempt(List.of(11L)))
                .thenReturn(MemoryVectorWriter.Attempt.deferred("记忆向量未就绪"));

        hook.afterSaved(expiredItem(11L));
        runDispatchedTask();

        // 未就绪只回 PENDING：一次断网不该把三振用完
        verify(items).markVectorSyncStatus(List.of(11L), MemoryVectorSyncStatus.PENDING);
        verify(items, never()).bumpVectorSyncFail(any(), any(), any());
    }

    @Test
    void removedIdsAreDedupedAndSyncStatusFollowsDeleteOutcome() {
        when(writer.removeAttempt(List.of(3L, 4L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        hook.afterRemoved(java.util.Arrays.asList(3L, 3L, null, 0L, 4L));

        // 刀8：先删点，成功才落 SYNCED（原来是无条件先标终态）
        verify(items, never()).markVectorSync(any(), any(), anyInt(), any());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(memoryTaskExecutor).execute(task.capture());
        task.getValue().run();

        verify(writer).removeAttempt(List.of(3L, 4L));
        verify(items).markVectorSync(List.of(3L, 4L), MemoryVectorSyncStatus.SYNCED, 0, null);
    }

    @Test
    void removedButDeleteFailedMarksFailedWithoutRollingBack() {
        when(writer.removeAttempt(List.of(5L)))
                .thenReturn(MemoryVectorWriter.Attempt.failed("MilvusException: unavailable"));

        hook.afterRemoved(List.of(5L));
        runDispatchedTask();

        // 主清理（MySQL 删除/过期）已经提交，这里只打标，绝不反向回滚
        verify(items).bumpVectorSyncFail(List.of(5L), MemoryVectorSyncStatus.FAILED,
                "MilvusException: unavailable");
    }

    @Test
    void removedButNotReadyKeepsPendingForNextRound() {
        when(writer.removeAttempt(List.of(5L)))
                .thenReturn(MemoryVectorWriter.Attempt.deferred("记忆向量未就绪"));

        hook.afterRemoved(List.of(5L));
        runDispatchedTask();

        verify(items).markVectorSyncStatus(List.of(5L), MemoryVectorSyncStatus.PENDING);
        verify(items, never()).bumpVectorSyncFail(any(), any(), any());
        verify(items, never()).markVectorSync(any(), any(), anyInt(), any());
    }

    @Test
    void emptyRemovedIdsDoNothing() {
        hook.afterRemoved(List.of());

        verifyNoInteractions(memoryTaskExecutor, writer, items);
    }

    @Test
    void rejectedRemoveTaskKeepsRowPendingSoReconcilerCanDeleteLater() {
        doThrow(new TaskRejectedException("queue full")).when(memoryTaskExecutor).execute(any(Runnable.class));

        assertDoesNotThrow(() -> hook.afterRemoved(List.of(5L)));

        // 投递被拒 = 这个点没人删：必须拉回 PENDING，否则停在 SYNCED 会让孤儿向量永久滞留
        verify(items).markVectorSyncStatus(List.of(5L), MemoryVectorSyncStatus.PENDING);
    }

    @Test
    void rejectedTaskDoesNotThrow() {
        doThrow(new TaskRejectedException("queue full")).when(memoryTaskExecutor).execute(any(Runnable.class));

        assertDoesNotThrow(() -> hook.afterSaved(item(11L, "LONG")));
        verify(writer, never()).attempt(any());
    }

    @Test
    void statusWriteFailureDoesNotBreakSavePath() {
        doThrow(new IllegalStateException("db down"))
                .when(items).markVectorSync(any(), any(), anyInt(), isNull());

        // 状态位落库失败只日志：主业务流程（已经提交的 MySQL 行）不受影响
        assertDoesNotThrow(() -> hook.afterSaved(item(11L, "LONG")));
    }

    @Test
    void snapshotIsDetachedFromCallerObject() {
        MemoryItem source = item(11L, "LONG");
        hook.afterSaved(source);
        // 调用方提交后继续改对象，不应影响已投递的快照
        source.setContent("");

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(memoryTaskExecutor).execute(task.capture());
        task.getValue().run();

        ArgumentCaptor<MemoryItem> written = ArgumentCaptor.forClass(MemoryItem.class);
        verify(writer).attempt(written.capture());
        assertEquals("用户住广州", written.getValue().getContent());
    }

    @Test
    void snapshotCarriesImportanceAndExpiryForVectorRow() {
        MemoryItem source = item(11L, "LONG");
        source.setImportance(80);
        LocalDateTime expired = LocalDateTime.of(2030, 1, 1, 0, 0);
        source.setExpiredTime(expired);

        hook.afterSaved(source);
        runDispatchedTask();

        ArgumentCaptor<MemoryItem> written = ArgumentCaptor.forClass(MemoryItem.class);
        verify(writer).attempt(written.capture());
        // 刀7：importance / expired_time 必须随快照进入向量行；漏拷会让向量侧永远写 0 / 永不过期
        assertEquals(80, written.getValue().getImportance());
        assertEquals(expired, written.getValue().getExpiredTime());
    }

    /**
     * 取出投递的任务并就地执行，模拟池线程运行（无需真实线程池）。
     */
    private void runDispatchedTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(memoryTaskExecutor).execute(task.capture());
        task.getValue().run();
    }

    private static MemoryItem expiredItem(Long id) {
        MemoryItem item = item(id, "LONG");
        item.setExpiredTime(LocalDateTime.now().minusMinutes(1));
        return item;
    }

    private static MemoryItem item(Long id, String scope) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setAgentId(1L);
        item.setUserId(2L);
        item.setScope(scope);
        item.setFactKey("residence_city");
        item.setContent("用户住广州");
        item.setStatus(1);
        return item;
    }
}
