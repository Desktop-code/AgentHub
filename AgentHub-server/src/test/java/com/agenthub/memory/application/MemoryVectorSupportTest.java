package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.domain.service.MemoryVectorStoreException;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆向量就绪编排（刀10）：无平台级不 ensure、快照维驱动 ensure、Rerank 不挡 ready、失败降级不抛。
 * 全部 mock，不连 Milvus、不调任何模型接口。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorSupportTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int SNAPSHOT_DIM = 1024;
    private static final long EMBEDDING_ID = 9L;
    private static final long RERANK_ID = 12L;

    @Mock
    private MemoryVectorModelSlotResolver slots;
    @Mock
    private MemoryVectorStore store;

    private MemoryProperties properties;
    private MemoryVectorSupport support;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setMilvusCollection(COLLECTION);
        // YAML 维度仍是 768，但本刀只认快照 1024：用于证明建集不再读 YAML
        properties.setEmbeddingDimension(768);
        support = new MemoryVectorSupport(properties, slots, store);
    }

    @Test
    void platformEmbeddingSnapshotDrivesEnsure() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, SNAPSHOT_DIM)));
        when(slots.rerank()).thenReturn(Optional.empty());

        MemoryVectorReadiness readiness = support.readiness();

        // ensure 必须用快照维（1024），而不是 YAML 的 768
        verify(store).ensureCollection(COLLECTION, SNAPSHOT_DIM);
        assertTrue(readiness.ready());
        assertEquals(COLLECTION, readiness.collectionName());
        assertEquals(SNAPSHOT_DIM, readiness.dimension());
        assertEquals(EMBEDDING_ID, readiness.embeddingInstanceId());
        // Rerank 缺失只影响重排，不挡 isReady
        assertFalse(readiness.rerankReady());
        assertTrue(readiness.reason().isEmpty());
    }

    @Test
    void platformRerankOnlyAffectsRerankFlag() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, SNAPSHOT_DIM)));
        when(slots.rerank()).thenReturn(Optional.of(instance(RERANK_ID, 1, "RERANK")));

        MemoryVectorReadiness readiness = support.readiness();

        assertTrue(readiness.ready());
        assertTrue(readiness.rerankReady());
        assertEquals(RERANK_ID, readiness.rerankInstanceId());
    }

    @Test
    void noPlatformEmbeddingDoesNotEnsureCollection() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.empty());

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("未设置平台级 Embedding"));
        // 无平台级时绝不建集合
        verifyNoInteractions(store);
    }

    @Test
    void disabledPlatformEmbeddingDoesNotEnsureCollection() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 0, SNAPSHOT_DIM)));

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("已停用"));
        verifyNoInteractions(store);
    }

    @Test
    void missingDimensionSnapshotMakesNotReadyWithoutMilvus() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, null)));

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("embedding_dim"));
        // 快照缺失 → 不 ensure、不调 Rerank
        verifyNoInteractions(store);
        verify(slots, never()).rerank();
    }

    @Test
    void dimensionMismatchIsNotSilentlyFixed() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, SNAPSHOT_DIM)));
        doThrow(new MemoryVectorStoreException("集合 agenthub_memory_long 向量维度 768 与平台级 Embedding 维度快照 1024 不一致，需人工重建该集合"))
                .when(store).ensureCollection(anyString(), anyInt());

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("不一致"), "原因应可观测：" + readiness.reason());
        // 不一致时只报错，绝不继续解析 Rerank 或尝试其它维度
        verify(store, times(1)).ensureCollection(COLLECTION, SNAPSHOT_DIM);
        verify(slots, never()).rerank();
    }

    @Test
    void ensureFailureDegradesToNotReadyAndDoesNotThrow() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, SNAPSHOT_DIM)));
        doThrow(new MemoryVectorStoreException("连接失败"))
                .when(store).ensureCollection(anyString(), anyInt());

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("Milvus"));
        verify(slots, never()).rerank();
    }

    @Test
    void collectionOutsideMemoryNamespaceMakesNotReady() {
        properties.setMilvusCollection("agenthub_kb_1");

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("agenthub_memory_"));
        verifyNoInteractions(store);
    }

    @Test
    void collectionDimensionConflictIsObservableAndNeverEnsures() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, SNAPSHOT_DIM)));
        // 现网集合是 768，快照是 1024：这是「需要受控重建」的信号，不是普通故障
        when(store.embeddingDimension(COLLECTION)).thenReturn(768);

        MemoryVectorReadiness readiness = support.readiness();

        assertFalse(readiness.ready());
        assertTrue(readiness.dimensionConflict());
        assertEquals(768, readiness.collectionDimension());
        assertEquals(SNAPSHOT_DIM, readiness.dimension());
        assertTrue(readiness.reason().contains("受控重建"), "原因应指出下一步：" + readiness.reason());
        // 冲突时不得调用 ensure（那会把「不一致」变成抛错日志，掩盖可执行的修复路径）
        verify(store, never()).ensureCollection(anyString(), anyInt());
    }

    @Test
    void readySnapshotIsCachedButInvalidateForcesReprobe() {
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(EMBEDDING_ID, 1, SNAPSHOT_DIM)));
        when(slots.rerank()).thenReturn(Optional.empty());

        support.readiness();
        support.readiness();
        verify(store, times(1)).ensureCollection(COLLECTION, SNAPSHOT_DIM);

        // 平台级变更后主动失效：下一次必须重新 ensure（幂等，不重建已有集合）
        support.invalidate();
        support.readiness();
        verify(store, times(2)).ensureCollection(COLLECTION, SNAPSHOT_DIM);
    }

    private static ModelInstance embedding(long id, int status, Integer dim) {
        ModelInstance instance = instance(id, status, "EMBEDDING");
        instance.setPlatformLocked(1);
        instance.setEmbeddingDim(dim);
        return instance;
    }

    private static ModelInstance instance(long id, int status, String capability) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setStatus(status);
        instance.setCapability(capability);
        return instance;
    }
}
