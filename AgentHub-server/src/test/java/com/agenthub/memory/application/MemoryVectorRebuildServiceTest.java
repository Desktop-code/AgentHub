package com.agenthub.memory.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.domain.service.MemoryVectorStoreException;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 受控重建的窄单测。所属域：memory。
 * <p>
 * 只验证「删不删 / 什么时候删 / 删完干什么」的契约，不连 Milvus、不连 MySQL、不打模型：
 * <ul>
 *   <li>缺 {@code confirmRebuild} → 一律拒绝，且绝不 DROP；</li>
 *   <li>集合名与配置不一致 → 拒绝，防误删其它集合；</li>
 *   <li>补同步中 / 重建执行中 → 拒绝，且不产生任何 store 调用；</li>
 *   <li>显式确认 → 顺序必须是 DROP → ensure(快照维) → 有效 LONG 打回 PENDING → 一轮补同步；</li>
 *   <li>DROP 失败 → 回滚重建态，不再 ensure；</li>
 *   <li>重建后仍有待补 → 重建态保持，禁止改平台级。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorRebuildServiceTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 1024;
    private static final long EMBEDDING_ID = 21L;

    @Mock
    private MemoryVectorSupport support;
    @Mock
    private MemoryVectorModelSlotResolver slots;
    @Mock
    private MemoryVectorStore store;
    @Mock
    private MemoryItemRepository items;
    @Mock
    private MemoryVectorReconciler reconciler;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private OperatorContext operatorContext;

    private MemoryProperties properties;
    private MemoryVectorRebuildState rebuildState;
    private MemoryVectorRebuildService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        rebuildState = new MemoryVectorRebuildState();
        service = new MemoryVectorRebuildService(support, slots, store, items, reconciler,
                rebuildState, properties, operLogWriter, operatorContext);
        // 缺确认标志等用例在取集合名之前就被拒，用 lenient 避免误报未使用桩
        lenient().when(support.configuredCollection()).thenReturn(COLLECTION);
    }

    @Test
    @DisplayName("缺确认标志：拒绝且绝不 DROP")
    void missingConfirmNeverDrops() {
        BizException error = assertThrows(BizException.class, () -> service.rebuild(COLLECTION, false));

        assertTrue(error.getMessage().contains("confirmRebuild"));
        verifyNoInteractions(store, items, slots);
    }

    @Test
    @DisplayName("集合名与配置不一致：拒绝，禁止指定任意集合")
    void wrongCollectionNameIsRejected() {
        assertThrows(BizException.class, () -> service.rebuild("agenthub_kb_1", true));
        assertThrows(BizException.class, () -> service.rebuild(null, true));

        verifyNoInteractions(store, items);
    }

    @Test
    @DisplayName("补同步进行中：拒绝重建，不产生任何 store 调用")
    void syncingBlocksRebuild() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(embedding()));
        when(reconciler.isRunning()).thenReturn(true);

        BizException error = assertThrows(BizException.class, () -> service.rebuild(COLLECTION, true));

        assertTrue(error.getMessage().contains("补同步进行中"));
        verifyNoInteractions(store, items);
    }

    @Test
    @DisplayName("重建执行中：CAS 抢锁失败即拒绝")
    void concurrentRebuildIsRejected() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(embedding()));
        when(reconciler.isRunning()).thenReturn(false);
        rebuildState.tryBegin();

        BizException error = assertThrows(BizException.class, () -> service.rebuild(COLLECTION, true));

        assertTrue(error.getMessage().contains("重建正在执行中"));
        verifyNoInteractions(store, items);
    }

    @Test
    @DisplayName("显式确认 + 同维：DROP → ensure(快照维) → 打回 PENDING → 一轮补同步")
    void confirmedRebuildDropsEnsuresRequeuesAndReconciles() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(embedding()));
        when(reconciler.isRunning()).thenReturn(false);
        when(support.collectionDimension()).thenReturn(768);
        when(items.requeueValidLong(any())).thenReturn(12);
        when(support.refresh())
                .thenReturn(com.agenthub.memory.domain.model.MemoryVectorReadiness
                        .ready(COLLECTION, DIMENSION, EMBEDDING_ID, null));
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 12, 12, 0, 0));
        when(reconciler.pendingCount()).thenReturn(0);
        when(operatorContext.requireUserId()).thenReturn(7L);

        MemoryVectorRebuildService.RebuildResult result = service.rebuild(COLLECTION, true);

        InOrder order = inOrder(store, items, reconciler);
        order.verify(store).dropCollection(COLLECTION);
        order.verify(store).ensureCollection(COLLECTION, DIMENSION);
        order.verify(items).requeueValidLong(any());
        order.verify(reconciler).runOnce();
        assertEquals(12, result.requeued());
        assertEquals(768, result.previousCollectionDimension());
        assertTrue(result.ready());
        // 全量补完 → 重建态收口，允许再次变更平台级
        assertFalse(rebuildState.isRebuilding());
        assertFalse(result.rebuilding());
    }

    @Test
    @DisplayName("仍有待补：重建态保持，平台级仍被锁")
    void pendingRemainsKeepsRebuildLock() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(embedding()));
        when(reconciler.isRunning()).thenReturn(false);
        when(support.collectionDimension()).thenReturn(null);
        when(items.requeueValidLong(any())).thenReturn(300);
        when(support.refresh())
                .thenReturn(com.agenthub.memory.domain.model.MemoryVectorReadiness
                        .ready(COLLECTION, DIMENSION, EMBEDDING_ID, null));
        when(reconciler.runOnce()).thenReturn(new MemoryVectorReconciler.Summary("done", 200, 200, 0, 0));
        when(reconciler.pendingCount()).thenReturn(100);
        when(operatorContext.requireUserId()).thenReturn(7L);

        MemoryVectorRebuildService.RebuildResult result = service.rebuild(COLLECTION, true);

        assertTrue(rebuildState.isRebuilding());
        assertTrue(result.rebuilding());
        assertEquals(100, result.pendingAfter());
        // 审计日志必须留痕，但不含任何密钥
        verify(operLogWriter).write(eq("memory"), eq("vector-rebuild"), eq("memory_collection"),
                eq(COLLECTION), anyString());
    }

    @Test
    @DisplayName("DROP 失败：集合未被破坏，回滚重建态且不再 ensure")
    void dropFailureAbortsState() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(embedding()));
        when(reconciler.isRunning()).thenReturn(false);
        when(support.collectionDimension()).thenReturn(768);
        doThrow(new MemoryVectorStoreException("milvus down")).when(store).dropCollection(COLLECTION);

        assertThrows(BizException.class, () -> service.rebuild(COLLECTION, true));

        assertFalse(rebuildState.isRebuilding());
        assertFalse(rebuildState.isRunning());
        verify(store, never()).ensureCollection(anyString(), anyInt());
        verifyNoInteractions(items);
    }

    @Test
    @DisplayName("ensure 失败（已 DROP）：保持重建态，绝不假装 ready")
    void ensureFailureAfterDropKeepsRebuildLock() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(embedding()));
        when(reconciler.isRunning()).thenReturn(false);
        when(support.collectionDimension()).thenReturn(768);
        doThrow(new MemoryVectorStoreException("create failed"))
                .when(store).ensureCollection(COLLECTION, DIMENSION);

        assertThrows(BizException.class, () -> service.rebuild(COLLECTION, true));

        // 集合已被删，存量还没重排：必须继续锁住平台级，避免再换维
        assertTrue(rebuildState.isRebuilding());
        assertFalse(rebuildState.isRunning());
        verify(support).invalidate();
        verifyNoInteractions(items);
    }

    @Test
    @DisplayName("平台级 Embedding 缺维度快照：拒绝重建")
    void missingSnapshotIsRejected() {
        ModelInstance instance = embedding();
        instance.setEmbeddingDim(null);
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(instance));

        BizException error = assertThrows(BizException.class, () -> service.rebuild(COLLECTION, true));

        assertTrue(error.getMessage().contains("embedding_dim"));
        verifyNoInteractions(store);
    }

    private static ModelInstance embedding() {
        ModelInstance instance = new ModelInstance();
        instance.setId(EMBEDDING_ID);
        instance.setDisplayName("嵌入实例");
        instance.setCapability(ModelCapability.EMBEDDING.name());
        instance.setEmbeddingDim(DIMENSION);
        instance.setStatus(1);
        instance.setLastTestOk(1);
        instance.setPlatformLocked(1);
        return instance;
    }
}
