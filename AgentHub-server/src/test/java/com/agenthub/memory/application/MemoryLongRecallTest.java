package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LONG 召回编排：过期丢弃、降级、过滤由 Milvus 负责、≤N、rerank 规则。
 * 全部 mock：不打真实 Embedding/Milvus/Rerank。
 */
@ExtendWith(MockitoExtension.class)
class MemoryLongRecallTest {

    private static final long AGENT_ID = 4L;
    private static final long USER_ID = 7L;
    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 4;
    /** 平台级 Embedding 实例 id（仅单测夹具；生产由模型中心平台锁定解析） */
    private static final long EMBEDDING_ID = 9L;
    /** 平台级 Rerank 实例 id（仅单测夹具） */
    private static final long RERANK_ID = 12L;

    @Mock
    private MemoryItemRepository items;
    @Mock
    private MemoryVectorRecall vectors;
    @Mock
    private RerankClient reranks;
    @Mock
    private MemoryRecallGateway gateway;

    private MemoryProperties properties;
    private MemoryLongRecall recall;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setRecallVectorTopK(20);
        properties.setRecallRerankTimeoutMs(800L);
        recall = new MemoryLongRecall(items, vectors, reranks, gateway, properties);
    }

    @Test
    void notReadyFallsBackToMysqlTopN() {
        when(vectors.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), any()))
                .thenReturn(List.of(item(1L, 80)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        verifyNoInteractions(reranks, gateway);
    }

    @Test
    void emptyQuerySkipsVectorAndUsesMysql() {
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), any()))
                .thenReturn(List.of(item(1L, 80)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "  ", 5);

        assertEquals(1, result.size());
        verifyNoInteractions(vectors, reranks, gateway);
    }

    @Test
    void vectorPathDropsRowsMissingInMysql() {
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        // 2 号已被逻辑删除/过期：listActiveByIds 不返回，必须被剔除
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 50)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void allCandidatesStaleFallsBackToMysql() {
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(2L, 0.8f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any())).thenReturn(List.of());
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), any()))
                .thenReturn(List.of(item(9L, 70)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(9L, result.get(0).getId());
    }

    @Test
    void withoutRerankKeepsImportanceOrderAndCutsToN() {
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f),
                        new MemoryVectorHit(3L, 0.7f)));
        // 向量序是 1,2,3；importance 序应为 3,1,2
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 50), item(2L, 10), item(3L, 90)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 2);

        assertEquals(2, result.size());
        assertEquals(3L, result.get(0).getId());
        assertEquals(1L, result.get(1).getId());
        verifyNoInteractions(reranks);
    }

    @Test
    void rerankReordersAndCutsToN() {
        when(vectors.readiness()).thenReturn(readyWithRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 90), item(2L, 10)));
        when(gateway.call(eq("rerank"), anyLong(), any()))
                .thenReturn(List.of(new RerankHit(1, 0.95), new RerankHit(0, 0.2)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 1);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
        // 重排只出一趟：实例 12、原查询、入参条数=limit
        verify(gateway).call(eq("rerank"), anyLong(), any());
    }

    @Test
    void rerankFailureFallsBackToImportanceOrder() {
        when(vectors.readiness()).thenReturn(readyWithRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 90), item(2L, 10)));
        // 网关在超时/异常时返回 null：必须退回 importance 序而不是丢空
        when(gateway.call(eq("rerank"), anyLong(), any())).thenReturn(null);

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void rerankEmptyResultFallsBackToImportanceOrder() {
        when(vectors.readiness()).thenReturn(readyWithRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 20)));
        when(gateway.call(eq("rerank"), anyLong(), any())).thenReturn(List.of());

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
    }

    @Test
    void rerankUsesInstanceTwelveAndOriginalQuery() {
        when(vectors.readiness()).thenReturn(readyWithRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 10)));
        // 网关真实实现会执行传入的动作；这里让 mock 代跑，才能断言端口入参
        when(gateway.call(eq("rerank"), anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
        when(reranks.rerank(RERANK_ID, "查天气", List.of("内容1"), 1))
                .thenReturn(List.of(new RerankHit(0, 0.9)));

        recall.topN(AGENT_ID, USER_ID, "查天气", 1);

        verify(reranks).rerank(RERANK_ID, "查天气", List.of("内容1"), 1);
    }

    @Test
    void rerankSkippedWhenAllContentsBlank() {
        MemoryItem blank = item(1L, 20);
        blank.setContent("   ");
        when(vectors.readiness()).thenReturn(readyWithRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any())).thenReturn(List.of(blank));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        // 正文全空时重排没有意义，一次出站都不该发生
        verify(gateway, never()).call(eq("rerank"), anyLong(), any());
    }

    @Test
    void vectorContentFillsBlankMysqlContent() {
        MemoryItem blank = item(1L, 20);
        blank.setContent("   ");
        when(vectors.readiness()).thenReturn(readyWithRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f, "向量侧正文", 20, 0L)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any())).thenReturn(List.of(blank));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        // MySQL 取回空正文时用向量副本兜底：正文不再只有「回 MySQL」一条路
        assertEquals("向量侧正文", result.get(0).getContent());
    }

    @Test
    void vectorHitsAreCappedToN() {
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 80), item(2L, 20)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 1);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void candidatesCountUsesConfiguredTopK() {
        properties.setRecallVectorTopK(30);
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), eq(30))).thenReturn(List.of());
        when(items.listActive(anyLong(), anyLong(), eq("LONG"), eq(3), any())).thenReturn(List.of());

        recall.topN(AGENT_ID, USER_ID, "查天气", 3);

        verify(vectors).candidates(AGENT_ID, USER_ID, "查天气", 30);
    }

    /**
     * importance 下限在向量路径同样生效：低分条目先丢，再截 Top N。
     */
    @Test
    void importanceFloorFiltersVectorCandidatesBeforeTopN() {
        properties.setRecallImportanceMin(50);
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 80), item(2L, 20)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    /**
     * MySQL 降级路径与向量路径同一门槛：下限必须传给仓储在 SQL 里过滤。
     */
    @Test
    void importanceFloorAppliesToMysqlFallback() {
        properties.setRecallImportanceMin(50);
        when(vectors.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), eq(50), any()))
                .thenReturn(List.of(item(1L, 80)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        verify(items).listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), eq(50), any());
    }

    /**
     * 向量候选全部低于下限时视为无可用候选，回落 MySQL 时仍带同一门槛。
     */
    @Test
    void importanceFloorEmptiesVectorCandidatesThenFallsBackToMysql() {
        properties.setRecallImportanceMin(50);
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f)));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(item(1L, 20)));
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(5), eq(50), any()))
                .thenReturn(List.of(item(9L, 90)));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(9L, result.get(0).getId());
    }

    /**
     * 去重开关默认 false：同 fact_key / 近义正文都原样保留，行为与现网一致。
     */
    @Test
    void nearDupDisabledKeepsDuplicates() {
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        MemoryItem first = item(1L, 80);
        first.setFactKey("same");
        first.setContent("用户住在杭州");
        MemoryItem second = item(2L, 70);
        second.setFactKey("same");
        second.setContent("用户在杭州居住");
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(first, second));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(2, result.size());
    }

    /**
     * 去重开启：同 fact_key 冲突留 importance 高者。
     */
    @Test
    void nearDupSameFactKeyKeepsHigherImportance() {
        properties.setRecallNearDupEnabled(true);
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        MemoryItem first = item(1L, 80);
        first.setFactKey("same");
        first.setContent("用户住在杭州");
        MemoryItem second = item(2L, 70);
        second.setFactKey("same");
        second.setContent("用户在杭州居住");
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(first, second));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    /**
     * 同 importance 时留 update_time 更新者；候选胜出要替换掉旧的冲突项。
     */
    @Test
    void nearDupTieKeepsNewerUpdateTime() {
        properties.setRecallNearDupEnabled(true);
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        MemoryItem older = item(1L, 80);
        older.setFactKey("same");
        older.setContent("用户住在杭州");
        MemoryItem newer = item(2L, 80);
        newer.setFactKey("same");
        newer.setContent("用户在杭州居住");
        newer.setUpdateTime(LocalDateTime.of(2026, 11, 2, 0, 0));
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(older, newer));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
    }

    /**
     * 向量路径按相似度阈值判近义：高相似正文只留 importance 高者。
     */
    @Test
    void nearDupSimilarityThresholdDropsSimilarContent() {
        properties.setRecallNearDupEnabled(true);
        properties.setRecallNearDupSimilarity(0.92);
        when(vectors.readiness()).thenReturn(readyWithoutRerank());
        when(vectors.candidates(eq(AGENT_ID), eq(USER_ID), eq("查天气"), anyInt()))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f), new MemoryVectorHit(2L, 0.8f)));
        MemoryItem high = item(1L, 90);
        high.setContent("用户喜欢喝美式咖啡");
        MemoryItem low = item(2L, 50);
        low.setContent("用户喜欢喝美式咖啡呀");
        when(items.listActiveByIds(any(Collection.class), eq("LONG"), any()))
                .thenReturn(List.of(high, low));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    /**
     * MySQL-only 没有向量分：用正文规范化指纹判等，标点/空白差异不算新条目。
     */
    @Test
    void nearDupMysqlPathUsesNormalizedFingerprint() {
        properties.setRecallNearDupEnabled(true);
        when(vectors.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));
        MemoryItem low = item(1L, 50);
        low.setContent("用户住在杭州。");
        MemoryItem high = item(2L, 80);
        high.setContent("用户 住在 杭州");
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), anyInt(), any()))
                .thenReturn(List.of(low, high));

        List<MemoryItem> result = recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        assertEquals(1, result.size());
        // 冲突裁决：importance 低的先入，也要被后面的高 importance 替换
        assertEquals(2L, result.get(0).getId());
    }

    /**
     * 去重开启时候选窗口要大于 N，去重后才截 Top N；关闭时仍只取 N。
     */
    @Test
    void nearDupEnabledFetchesExtraMysqlCandidates() {
        properties.setRecallNearDupEnabled(true);
        when(vectors.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));
        when(items.listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(15), any()))
                .thenReturn(List.of(item(1L, 80)));

        recall.topN(AGENT_ID, USER_ID, "查天气", 5);

        verify(items).listActive(eq(AGENT_ID), eq(USER_ID), eq("LONG"), eq(15), any());
    }

    private static MemoryVectorReadiness readyWithoutRerank() {
        return MemoryVectorReadiness.ready(COLLECTION, DIMENSION, EMBEDDING_ID, null);
    }

    private static MemoryVectorReadiness readyWithRerank() {
        return MemoryVectorReadiness.ready(COLLECTION, DIMENSION, EMBEDDING_ID,
                RERANK_ID);
    }

    private static MemoryItem item(long id, int importance) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setAgentId(AGENT_ID);
        item.setUserId(USER_ID);
        item.setScope("LONG");
        item.setFactKey("k" + id);
        item.setContent("内容" + id);
        item.setImportance(importance);
        item.setStatus(1);
        item.setUpdateTime(LocalDateTime.of(2026, 11, 1, 0, 0));
        return item;
    }
}
