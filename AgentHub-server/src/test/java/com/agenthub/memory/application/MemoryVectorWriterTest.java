package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.model.MemoryVectorRow;
import com.agenthub.memory.domain.service.MemoryVectorSchema;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.domain.service.MemoryVectorStoreException;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.EmbeddingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 向量写入器：实例 9 + YAML 维度、空正文不出站、未就绪 skip、有限次重试、失败只日志不回滚。
 * 全部 mock，不连 Milvus、不打真实 Embedding。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorWriterTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 4;
    /** 平台级 Embedding 实例 id（仅单测夹具；生产由 model_instance.platform_locked 解析） */
    private static final long EMBEDDING_ID = 9L;
    @Mock
    private MemoryVectorSupport support;
    @Mock
    private EmbeddingClient embeddings;
    @Mock
    private MemoryVectorStore store;

    private MemoryProperties properties;
    private MemoryVectorWriter writer;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setEmbeddingDimension(DIMENSION);
        // 默认不打真实等待：单测里退避必须为 0
        properties.setVectorWriteRetryBackoffMs(0L);
        writer = new MemoryVectorWriter(support, embeddings, store, properties);
    }

    @Test
    void notReadySkipsEmbeddingAndStore() {
        when(support.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));

        writer.upsert(item(11L, "用户住广州"));

        verifyNoInteractions(embeddings, store);
    }

    @Test
    void readinessFailureIsTreatedAsNotReady() {
        when(support.readiness()).thenThrow(new IllegalStateException("milvus client init failed"));

        writer.upsert(item(11L, "用户住广州"));

        verifyNoInteractions(embeddings, store);
    }

    @Test
    void blankContentSkipsEmbedding() {
        writer.upsert(item(11L, "   "));

        // 空正文连就绪判断都不需要，直接不出站
        verifyNoInteractions(support, embeddings, store);
    }

    @Test
    void upsertUsesInstanceNineAndNormalizesVector() {
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenReturn(new float[]{3f, 4f, 0f, 0f});

        writer.upsert(item(11L, "用户住广州"));

        ArgumentCaptor<MemoryVectorRow> captor = ArgumentCaptor.forClass(MemoryVectorRow.class);
        verify(store).upsert(eq(COLLECTION), captor.capture());
        MemoryVectorRow row = captor.getValue();
        assertEquals(11L, row.memoryId());
        assertEquals(2L, row.userId());
        assertEquals(1L, row.agentId());
        assertEquals("residence_city", row.factKey());
        // IP 索引要求 L2 归一化：{3,4} → {0.6,0.8}
        assertEquals(0.6f, row.embedding()[0], 1e-6);
        assertEquals(0.8f, row.embedding()[1], 1e-6);
    }

    @Test
    void upsertCarriesExpiryImportanceAndContentCopy() {
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenReturn(new float[]{1f, 0f, 0f, 0f});
        MemoryItem item = item(11L, "用户住广州");
        item.setImportance(80);
        LocalDateTime expired = LocalDateTime.of(2030, 1, 1, 0, 0);
        item.setExpiredTime(expired);

        writer.upsert(item);

        ArgumentCaptor<MemoryVectorRow> captor = ArgumentCaptor.forClass(MemoryVectorRow.class);
        verify(store).upsert(eq(COLLECTION), captor.capture());
        MemoryVectorRow row = captor.getValue();
        // 三列与 MySQL 同一快照：过期时间按 Asia/Shanghai 转毫秒，重要度同语义，正文存副本
        assertEquals(MemoryVectorSchema.toEpochMillis(expired), row.expiredTime());
        assertEquals(80, row.importance());
        assertEquals("用户住广州", row.content());
    }

    @Test
    void upsertUsesZeroSentinelsWhenExpiryAndImportanceMissing() {
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenReturn(new float[]{1f, 0f, 0f, 0f});

        writer.upsert(item(11L, "用户住广州"));

        ArgumentCaptor<MemoryVectorRow> captor = ArgumentCaptor.forClass(MemoryVectorRow.class);
        verify(store).upsert(eq(COLLECTION), captor.capture());
        // 无过期写哨兵 0，召回过滤才会命中；importance null 按 0 落库
        assertEquals(MemoryVectorSchema.NO_EXPIRY_MILLIS, captor.getValue().expiredTime());
        assertEquals(0, captor.getValue().importance());
    }

    @Test
    void dimensionMismatchDoesNotReachStore() {
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenReturn(new float[]{1f, 2f});

        writer.upsert(item(11L, "用户住广州"));

        verify(store, never()).upsert(anyString(), any());
    }

    @Test
    void embeddingIsRetriedThenSucceeds() {
        properties.setVectorWriteMaxAttempts(3);
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenThrow(new EmbeddingException("第一次失败"))
                .thenThrow(new EmbeddingException("第二次失败"))
                .thenReturn(new float[]{1f, 0f, 0f, 0f});

        writer.upsert(item(11L, "用户住广州"));

        verify(embeddings, times(3)).embedOne(anyLong(), anyString(), anyInt());
        verify(store).upsert(eq(COLLECTION), any());
    }

    @Test
    void exhaustedAttemptsLogOnlyAndNeverThrow() {
        properties.setVectorWriteMaxAttempts(2);
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenThrow(new EmbeddingException("模型不可用"));

        assertDoesNotThrow(() -> writer.upsert(item(11L, "用户住广州")));

        verify(embeddings, times(2)).embedOne(anyLong(), anyString(), anyInt());
        verify(store, never()).upsert(anyString(), any());
    }

    @Test
    void storeFailureDoesNotThrow() {
        properties.setVectorWriteMaxAttempts(2);
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenReturn(new float[]{1f, 0f, 0f, 0f});
        org.mockito.Mockito.doThrow(new MemoryVectorStoreException("milvus down"))
                .when(store).upsert(anyString(), any());

        assertDoesNotThrow(() -> writer.upsert(item(11L, "用户住广州")));

        verify(store, times(2)).upsert(eq(COLLECTION), any());
    }

    @Test
    void removeDedupesAndSkipsInvalidIds() {
        when(support.readiness()).thenReturn(ready());

        writer.remove(Arrays.asList(5L, 5L, null, -1L, 6L));

        verify(store).remove(COLLECTION, List.of(5L, 6L));
    }

    @Test
    void removeAttemptReportsSyncedOnSuccess() {
        when(support.readiness()).thenReturn(ready());

        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of(5L, 6L));

        assertEquals(MemoryVectorWriter.SyncOutcome.SYNCED, attempt.outcome());
        verify(store).remove(COLLECTION, List.of(5L, 6L));
    }

    @Test
    void removeAttemptReportsDeferredWhenNotReady() {
        when(support.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));

        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of(5L));

        // 未就绪不是失败：调用方据此保持 PENDING，不烧三振
        assertEquals(MemoryVectorWriter.SyncOutcome.DEFERRED, attempt.outcome());
        verify(store, never()).remove(anyString(), any());
    }

    @Test
    void removeAttemptReportsFailureWithShortReason() {
        properties.setVectorWriteMaxAttempts(1);
        when(support.readiness()).thenReturn(ready());
        org.mockito.Mockito.doThrow(new MemoryVectorStoreException("milvus down"))
                .when(store).remove(anyString(), any());

        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of(5L));

        assertEquals(MemoryVectorWriter.SyncOutcome.FAILED, attempt.outcome());
        assertEquals("MemoryVectorStoreException: milvus down", attempt.reason());
    }

    @Test
    void removeAttemptSplitsBatchesByLimit() {
        properties.setVectorDeleteBatchLimit(2);
        when(support.readiness()).thenReturn(ready());

        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of(1L, 2L, 3L));

        assertEquals(MemoryVectorWriter.SyncOutcome.SYNCED, attempt.outcome());
        // 单次出站最多 2 个主键：一次过期清理不能把全部 id 塞进一个请求
        verify(store).remove(COLLECTION, List.of(1L, 2L));
        verify(store).remove(COLLECTION, List.of(3L));
    }

    @Test
    void removeAttemptStopsAtFirstFailedBatch() {
        properties.setVectorDeleteBatchLimit(1);
        properties.setVectorWriteMaxAttempts(1);
        when(support.readiness()).thenReturn(ready());
        org.mockito.Mockito.doThrow(new MemoryVectorStoreException("milvus down"))
                .when(store).remove(anyString(), any());

        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of(1L, 2L));

        assertEquals(MemoryVectorWriter.SyncOutcome.FAILED, attempt.outcome());
        // 第一批就失败：不再继续打后续批次，避免故障时连环出站
        verify(store, times(1)).remove(eq(COLLECTION), any());
    }

    @Test
    void removeAttemptOnEmptyListIsSyncedWithoutOutbound() {
        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of());

        assertEquals(MemoryVectorWriter.SyncOutcome.SYNCED, attempt.outcome());
        verifyNoInteractions(support, store);
    }

    @Test
    void removeSkipsWhenNotReady() {
        when(support.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));

        writer.remove(List.of(5L));

        verify(store, never()).remove(anyString(), any());
    }

    @Test
    void removeLogsOnlyOnFailure() {
        properties.setVectorWriteMaxAttempts(1);
        when(support.readiness()).thenReturn(ready());
        org.mockito.Mockito.doThrow(new MemoryVectorStoreException("milvus down"))
                .when(store).remove(anyString(), any());

        assertDoesNotThrow(() -> writer.remove(List.of(5L)));

        verify(store, times(1)).remove(eq(COLLECTION), any());
    }

    @Test
    void emptyRemoveListDoesNothing() {
        writer.remove(List.of());

        verifyNoInteractions(support, store);
    }

    @Test
    void clipKeepsEmbedTextBounded() {
        char[] big = new char[MemoryVectorWriter.MAX_EMBED_CHARS + 100];
        Arrays.fill(big, '字');
        when(support.readiness()).thenReturn(ready());
        when(embeddings.embedOne(eq(EMBEDDING_ID),
                eq(new String(big, 0, MemoryVectorWriter.MAX_EMBED_CHARS)), eq(DIMENSION)))
                .thenReturn(new float[]{1f, 0f, 0f, 0f});

        writer.upsert(item(11L, new String(big)));

        verify(embeddings).embedOne(eq(EMBEDDING_ID),
                eq(new String(big, 0, MemoryVectorWriter.MAX_EMBED_CHARS)), eq(DIMENSION));
    }

    private static MemoryVectorReadiness ready() {
        return MemoryVectorReadiness.ready(COLLECTION, DIMENSION,
                EMBEDDING_ID, null);
    }

    private static MemoryItem item(long id, String content) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setAgentId(1L);
        item.setUserId(2L);
        item.setScope("LONG");
        item.setFactKey("residence_city");
        item.setContent(content);
        item.setStatus(1);
        return item;
    }
}
