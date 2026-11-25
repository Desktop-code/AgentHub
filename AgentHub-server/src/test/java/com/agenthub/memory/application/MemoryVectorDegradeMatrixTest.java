package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorRow;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.domain.service.MemoryVectorStoreException;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LONG Milvus 降级矩阵（刀4 验收钉死项）。所属域：memory 的 application 单测。
 * <p>
 * 覆盖六行固定矩阵，每一行都以「主路径仍然可用」为断言落点：
 * <ol>
 *   <li><b>全就绪</b>：双写 upsert 成功 + 召回走向量并重排；</li>
 *   <li><b>实例 9 无效</b>：不 embed、不写向量，召回回落 MySQL Top N；</li>
 *   <li><b>Milvus 不可达</b>：ensure 失败 → 不写向量，召回回落 MySQL Top N；</li>
 *   <li><b>双写失败</b>：MySQL 已提交，向量失败只记日志，不抛、不回滚；</li>
 *   <li><b>召回超时</b>：等待上限到点即回落 MySQL Top N，不拖住主线程；</li>
 *   <li><b>实例 12 无效</b>：跳过 rerank，按 importance + 更新时间截 N。</li>
 * </ol>
 * 全部使用 mock 端口与本地线程池：不连 Milvus、不打真实 Embedding / Rerank、不启动 Spring 容器。
 * 矩阵里刻意组装真实链路（Support → Writer → Hook；Recall → Gateway → LongRecall），
 * 而不是全部打桩，否则降级行为会被桩掩盖。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorDegradeMatrixTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 4;
    private static final long AGENT_ID = 4L;
    private static final long USER_ID = 7L;
    /** 平台级 Embedding 实例 id（仅单测夹具；生产由模型中心平台锁定解析） */
    private static final long EMBEDDING_ID = 9L;
    /** 平台级 Rerank 实例 id（仅单测夹具） */
    private static final long RERANK_ID = 12L;

    @Mock
    private MemoryVectorModelSlotResolver slots;
    @Mock
    private MemoryVectorStore store;
    @Mock
    private EmbeddingClient embeddings;
    @Mock
    private RerankClient reranks;
    @Mock
    private com.agenthub.memory.domain.repository.MemoryItemRepository items;
    @Mock
    private ThreadPoolTaskExecutor memoryTaskExecutor;

    private MemoryProperties properties;
    private MemoryVectorSupport support;
    private MemoryVectorWriter writer;
    private MemoryVectorHook hook;
    private ThreadPoolTaskExecutor recallPool;
    private MemoryRecallGateway gateway;
    private MemoryLongRecall recall;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setMilvusCollection(COLLECTION);
        properties.setEmbeddingDimension(DIMENSION);
        // 矩阵不看退避：写侧重试间隔必须为 0，否则单测会真的 sleep
        properties.setVectorWriteRetryBackoffMs(0L);
        properties.setVectorWriteMaxAttempts(3);
        properties.setRecallVectorTopK(20);
        properties.setRecallRerankTimeoutMs(500L);
        properties.setRecallVectorMaxAttempts(1);

        support = new MemoryVectorSupport(properties, slots, store);
        writer = new MemoryVectorWriter(support, embeddings, store, properties);
        hook = new MemoryVectorHook(memoryTaskExecutor, writer, items);

        // 投递即执行：把池线程语义压成同步，保证断言能直接看到写入结果。
        // lenient：部分矩阵行只测召回、不触发双写，此桩在该行属未使用。
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(memoryTaskExecutor).execute(any(Runnable.class));

        recallPool = new ThreadPoolTaskExecutor();
        recallPool.setCorePoolSize(1);
        recallPool.setMaxPoolSize(1);
        recallPool.setQueueCapacity(8);
        recallPool.setThreadNamePrefix("memory-matrix-");
        recallPool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        recallPool.setDaemon(true);
        recallPool.initialize();
        gateway = new MemoryRecallGateway(recallPool);

        MemoryVectorRecall vectorRecall = new MemoryVectorRecall(support, embeddings, store, gateway, properties);
        recall = new MemoryLongRecall(items, vectorRecall, reranks, gateway, properties);
    }

    @AfterEach
    void tearDown() {
        recallPool.shutdown();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * 行 1：全就绪。写入走实例 9 + YAML collection，召回走向量并用实例 12 重排。
     */
    @Test
    void row1AllReadyWritesVectorAndRecallsByVector() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(instance(EMBEDDING_ID)));
        when(slots.rerank()).thenReturn(Optional.of(instance(RERANK_ID)));
        when(embeddings.embedOne(EMBEDDING_ID, "用户住广州", DIMENSION))
                .thenReturn(new float[]{3f, 4f, 0f, 0f});
        when(store.search(eq(COLLECTION), eq(USER_ID), eq(AGENT_ID), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(11L, 0.9f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(11L, 50)));
        when(reranks.rerank(eq(RERANK_ID), eq("用户住广州"), any(), anyInt()))
                .thenReturn(List.of(new RerankHit(0, 0.88)));

        // 写侧：embed(9) + upsert(YAML collection, 归一化向量)
        hook.afterSaved(item(11L, 50));
        verify(embeddings).embedOne(EMBEDDING_ID, "用户住广州", DIMENSION);
        verify(store).upsert(eq(COLLECTION), any(MemoryVectorRow.class));

        // 召回侧：向量命中 → MySQL 对齐 → 实例 12 重排
        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "用户住广州", 5);
        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).getId());
        verify(reranks).rerank(eq(RERANK_ID), eq("用户住广州"), any(), anyInt());
        // 全就绪时不得走 MySQL Top N 兜底
        verify(items, never()).listActive(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /**
     * 行 2：实例 9 无效。写侧一次出站都不发，召回直接 MySQL Top N。
     */
    @Test
    void row2EmbeddingSlotInvalidSkipsWriteAndFallsBackToMysql() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.empty());
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), any()))
                .thenReturn(List.of(item(11L, 50)));

        hook.afterSaved(item(11L, 50));
        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "用户住广州", 5);

        // 9 无效：不 embed、不 upsert、不检索
        verifyNoInteractions(embeddings);
        verify(store, never()).upsert(anyString(), any());
        verify(store, never()).search(anyString(), any(), any(), any(), anyInt());
        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).getId());
    }

    /**
     * 行 3：Milvus 不可达。ensure 抛异常 → 不写向量、不检索，召回回落 MySQL。
     */
    @Test
    void row3MilvusUnreachableSkipsWriteAndFallsBackToMysql() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(instance(EMBEDDING_ID)));
        doThrow(new MemoryVectorStoreException("连接失败")).when(store).ensureCollection(anyString(), anyInt());
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), any()))
                .thenReturn(List.of(item(11L, 50)));

        assertDoesNotThrow(() -> hook.afterSaved(item(11L, 50)));
        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "用户住广州", 5);

        verifyNoInteractions(embeddings);
        verify(store, never()).upsert(anyString(), any());
        verify(store, never()).search(anyString(), any(), any(), any(), anyInt());
        assertEquals(1, result.size());
    }

    /**
     * 行 4：双写失败不回滚。MySQL 已提交（事务 afterCommit 才投递），向量 upsert 全败只记日志。
     */
    @Test
    void row4VectorFailureAfterCommitNeverThrows() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(instance(EMBEDDING_ID)));
        when(embeddings.embedOne(anyLong(), anyString(), anyInt())).thenReturn(new float[]{1f, 0f, 0f, 0f});
        doThrow(new MemoryVectorStoreException("milvus down")).when(store).upsert(anyString(), any());

        // 模拟写库事务：MySQL 提交 → afterCommit 才投递向量任务
        TransactionSynchronizationManager.initSynchronization();
        hook.afterSaved(item(11L, 50));
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));

        // 重试次数耗尽即止，异常绝不外溢到事务收尾
        verify(store, org.mockito.Mockito.times(3)).upsert(eq(COLLECTION), any());
    }

    /**
     * 行 5：召回超时。等待上限到点立刻回落 MySQL，主线程不被出站拖住。
     */
    @Test
    void row5RecallTimeoutFallsBackToMysqlWithinBudget() {
        properties.setRecallVectorTimeoutMs(50L);
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(instance(EMBEDDING_ID)));
        // 出站远慢于等待上限：网关必须在 50ms 左右放手
        when(embeddings.embedOne(anyLong(), anyString(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(600L);
            return new float[]{1f, 0f, 0f, 0f};
        });
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), any()))
                .thenReturn(List.of(item(11L, 50)));

        long started = System.nanoTime();
        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "用户住广州", 5);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(1, result.size());
        assertTrue(elapsedMs < 1000L, "超时必须按上限降级，实际耗时 " + elapsedMs + "ms");
        verify(store, never()).search(anyString(), any(), any(), any(), anyInt());
    }

    /**
     * 行 6：实例 12 无效。跳过重排，按 importance 降序 + 更新时间截 N。
     */
    @Test
    void row6RerankSlotInvalidSkipsRerankAndRanksByImportance() {
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.of(instance(EMBEDDING_ID)));
        when(slots.rerank()).thenReturn(Optional.empty());
        when(embeddings.embedOne(anyLong(), anyString(), anyInt())).thenReturn(new float[]{1f, 0f, 0f, 0f});
        when(store.search(eq(COLLECTION), eq(USER_ID), eq(AGENT_ID), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(11L, 0.9f), new MemoryVectorHit(12L, 0.8f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(11L, 10), item(12L, 90)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "用户住广州", 1);

        assertEquals(1, result.size());
        // 向量序是 11,12；12 无效后应按 importance 取 12（90 > 10）
        assertEquals(12L, result.get(0).getId());
        verifyNoInteractions(reranks);
    }

    private static ModelInstance instance(long id) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setStatus(1);
        instance.setPlatformLocked(1);
        // 维度来自平台级实例的连通测试快照，不再读 YAML
        instance.setEmbeddingDim(DIMENSION);
        return instance;
    }

    private static MemoryItem item(long id, int importance) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setAgentId(AGENT_ID);
        item.setUserId(USER_ID);
        item.setScope("LONG");
        item.setFactKey("k" + id);
        item.setContent(id == 11L ? "用户住广州" : "内容" + id);
        item.setImportance(importance);
        item.setStatus(1);
        item.setUpdateTime(LocalDateTime.of(2026, 11, 1, 0, 0));
        return item;
    }
}
