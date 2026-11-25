package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 存量补同步：状态位驱动的补齐。
 * <p>
 * 覆盖 hard 约束：未就绪整轮跳过且不改状态位、空正文不出站落终态、成功清零、失败累加、
 * 中途未就绪保持 PENDING、单轮条数封顶、单 Agent 查询失败不拖垮整轮、并发只跑一轮。
 * 全部 mock，不连 Milvus、不打真实 Embedding。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorReconcilerTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 4;
    private static final long AGENT_ID = 4L;

    @Mock
    private MemoryItemRepository items;
    @Mock
    private MemoryVectorSupport support;
    @Mock
    private MemoryVectorWriter writer;

    private MemoryProperties properties;
    private MemoryVectorReconciler reconciler;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setMilvusCollection(COLLECTION);
        properties.setEmbeddingDimension(DIMENSION);
        properties.setVectorReconcileBatchLimit(200);
        properties.setVectorReconcileMaxFailCount(3);
        reconciler = new MemoryVectorReconciler(items, support, writer, properties);
    }

    @Test
    void notReadySkipsWholeRoundWithoutTouchingStatus() {
        when(support.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals("not_ready", summary.reason());
        assertEquals(0, summary.scanned());
        // 未就绪不查候选、不改状态位：等基础设施恢复后再补，避免误标 FAILED 烧掉次数
        verifyNoInteractions(items, writer);
    }

    @Test
    void readinessExceptionTreatedAsNotReady() {
        when(support.readiness()).thenThrow(new IllegalStateException("milvus client init failed"));

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals("not_ready", summary.reason());
        verifyNoInteractions(items, writer);
    }

    @Test
    void successMarksSyncedAndResetsFailCount() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 2)));
        when(writer.attempt(any(MemoryItem.class))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.synced());
        assertEquals(0, summary.failed());
        // 成功必须清零失败次数与 error，否则下一个失败会接着累加
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.SYNCED, 0, null);
    }

    @Test
    void failureIncrementsCountAndStoresShortReason() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 1)));
        when(writer.attempt(any(MemoryItem.class)))
                .thenReturn(MemoryVectorWriter.Attempt.failed("EmbeddingException: boom"));

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.failed());
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.FAILED, 2,
                "EmbeddingException: boom");
    }

    @Test
    void writerThrowingIsContainedToSingleRow() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 0), item(12L, "用户养猫", 0)));
        // 第一行直接炸（理论上写入器已收敛，这里验证兜底），第二行正常
        when(writer.attempt(any(MemoryItem.class)))
                .thenThrow(new IllegalStateException("unexpected"))
                .thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(2, summary.scanned());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.synced());
    }

    @Test
    void blankContentDeletesPointAndMarksTerminalWithoutEmbedding() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(item(11L, "   ", 0)));
        when(writer.removeAttempt(List.of(11L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.synced());
        // 空正文不 Embedding：转删点后落终态（残留点也顺手清掉），否则每轮都会被捞到、永远失败
        verify(writer, never()).attempt(any());
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.SYNCED, 0, null);
    }

    @Test
    void expiredCandidateDuringRoundIsDeletedNotEmbedded() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        // 候选查询返回后这一行刚好过期：时间窗守卫必须拦住，禁止再 upsert 正文
        MemoryItem expired = item(11L, "用户住广州", 0);
        expired.setExpiredTime(LocalDateTime.now().minusSeconds(1));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(expired));
        when(writer.removeAttempt(List.of(11L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.synced());
        verify(writer, never()).attempt(any());
        verify(items).markVectorSync(List.of(11L), MemoryVectorSyncStatus.SYNCED, 0, null);
    }

    @Test
    void deletePhaseRemovesExpiredOrDeletedPointsAndMarksSynced() {
        when(support.readiness()).thenReturn(ready());
        when(items.listVectorDeleteCandidates(any(), eq(200), eq(3))).thenReturn(List.of(7L, 8L));
        when(items.listLongAgentIds()).thenReturn(List.of());
        when(writer.removeAttempt(List.of(7L, 8L))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        // 已删/过期行的点必须真删；点本来就不存在也算成功 → SYNCED 终态
        assertEquals(2, summary.synced());
        verify(items).markVectorSync(List.of(7L, 8L), MemoryVectorSyncStatus.SYNCED, 0, null);
        // 不重新向量化：这些行不该有向量
        verify(writer, never()).attempt(any());
    }

    @Test
    void deletePhaseFailureBumpsFailCountWithoutRollingBack() {
        when(support.readiness()).thenReturn(ready());
        when(items.listVectorDeleteCandidates(any(), eq(200), eq(3))).thenReturn(List.of(9L));
        when(items.listLongAgentIds()).thenReturn(List.of());
        when(writer.removeAttempt(List.of(9L)))
                .thenReturn(MemoryVectorWriter.Attempt.failed("MilvusException: unavailable"));

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.failed());
        // 失败只打标 + 次数自增；MySQL 侧删除/过期早已提交，绝不回滚
        verify(items).bumpVectorSyncFail(List.of(9L), MemoryVectorSyncStatus.FAILED,
                "MilvusException: unavailable");
    }

    @Test
    void deletePhaseFailureDoesNotBreakFollowingUpsertPhase() {
        when(support.readiness()).thenReturn(ready());
        when(items.listVectorDeleteCandidates(any(), eq(200), eq(3)))
                .thenThrow(new IllegalStateException("db timeout"));
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 0)));
        when(writer.attempt(any(MemoryItem.class))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        // 删点候选查询失败只影响本阶段，补写阶段照常推进
        assertEquals(1, summary.synced());
    }

    @Test
    void deferredKeepsPendingAndIsCountedAsSkipped() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), eq(200), eq(3), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 0)));
        when(writer.attempt(any(MemoryItem.class)))
                .thenReturn(MemoryVectorWriter.Attempt.deferred("记忆向量未就绪"));

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.skipped());
        // 中途变未就绪：不改状态位（PENDING 留着下一轮）
        verify(items, never()).markVectorSync(any(), any(), anyInt(), any());
    }

    @Test
    void singleRoundIsCappedAcrossAgents() {
        properties.setVectorReconcileBatchLimit(2);
        reconciler = new MemoryVectorReconciler(items, support, writer, properties);
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(1L, 2L, 3L));
        // 第一个 Agent 吃掉 1 条，剩 1 条额度给第二个 Agent：第二轮传下去的 limit 必须是 1
        when(items.listVectorSyncCandidates(eq(1L), eq(2), eq(3), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 0)));
        when(items.listVectorSyncCandidates(eq(2L), eq(1), eq(3), any()))
                .thenReturn(List.of(item(12L, "用户养猫", 0)));
        when(writer.attempt(any(MemoryItem.class))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(2, summary.scanned());
        assertEquals(2, summary.synced());
        // 额度用尽后不再查后续 Agent，避免一轮变成全量
        verify(items, never()).listVectorSyncCandidates(eq(3L), anyInt(), anyInt(), any());
    }

    @Test
    void oneAgentQueryFailureDoesNotBreakRound() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(1L, AGENT_ID));
        when(items.listVectorSyncCandidates(eq(1L), anyInt(), anyInt(), any()))
                .thenThrow(new IllegalStateException("db timeout"));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), anyInt(), anyInt(), any()))
                .thenReturn(List.of(item(11L, "用户住广州", 0)));
        when(writer.attempt(any(MemoryItem.class))).thenReturn(MemoryVectorWriter.Attempt.synced());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals(1, summary.scanned());
        assertEquals(1, summary.synced());
    }

    @Test
    void concurrentRoundReturnsAlreadyRunning() {
        when(support.readiness()).thenReturn(ready());
        // 在第一轮执行中重入：模拟并发触发，返回 already_running 且不重复出站
        when(items.listLongAgentIds()).thenAnswer(invocation -> {
            MemoryVectorReconciler.Summary nested = reconciler.runOnce();
            assertEquals("already_running", nested.reason());
            return List.of();
        });

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals("done", summary.reason());
    }

    @Test
    void pendingCountUsesConfiguredMaxFailCountAndSwallowsFailure() {
        when(items.countVectorSyncCandidates(eq(3), any())).thenReturn(7);
        assertEquals(7, reconciler.pendingCount());

        when(items.countVectorSyncCandidates(eq(3), any())).thenThrow(new IllegalStateException("db down"));
        // 只用于回显，读失败不能让运维接口整体失败
        assertEquals(-1, reconciler.pendingCount());
    }

    @Test
    void maxFailCountIsPassedDownToRepository() {
        properties.setVectorReconcileMaxFailCount(5);
        reconciler = new MemoryVectorReconciler(items, support, writer, properties);
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of(AGENT_ID));
        when(items.listVectorSyncCandidates(eq(AGENT_ID), anyInt(), eq(5), any())).thenReturn(List.of());

        reconciler.runOnce();

        verify(items).listVectorSyncCandidates(eq(AGENT_ID), anyInt(), eq(5), any());
    }

    @Test
    void noLongAgentsCompletesWithoutOutbound() {
        when(support.readiness()).thenReturn(ready());
        when(items.listLongAgentIds()).thenReturn(List.of());

        MemoryVectorReconciler.Summary summary = reconciler.runOnce();

        assertEquals("done", summary.reason());
        assertEquals(0, summary.scanned());
        verifyNoInteractions(writer);
        verify(items, never()).listVectorSyncCandidates(anyLong(), anyInt(), anyInt(), any());
    }

    private static MemoryVectorReadiness ready() {
        return MemoryVectorReadiness.ready(COLLECTION, DIMENSION, 9L, null);
    }

    private static MemoryItem item(long id, String content, int failCount) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setAgentId(AGENT_ID);
        item.setUserId(2L);
        item.setScope("LONG");
        item.setFactKey("residence_city");
        item.setContent(content);
        item.setStatus(1);
        item.setExpiredTime(LocalDateTime.now().plusDays(1));
        item.setVectorSyncFailCount(failCount);
        return item;
    }
}
