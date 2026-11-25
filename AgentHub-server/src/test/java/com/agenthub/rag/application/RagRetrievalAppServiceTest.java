package com.agenthub.rag.application;

import com.agenthub.common.error.BizException;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.EmbeddingException;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankHit;
import com.agenthub.spi.rag.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索链路、截断与 DENSE_ONLY 退化。所属域：rag 的 application。③ 期验收主用例。
 * <p>
 * 全部替身：EmbeddingClient 与 RagProvider 都是 mock，**零真实出站**。
 * 覆盖点：
 * <ol>
 *   <li>DENSE_ONLY：只走向量，不调 BM25，不报错；</li>
 *   <li>DENSE_BM25：向量 + BM25 → RRF 融合；</li>
 *   <li>topK 截断与单条/总长截断生效；</li>
 *   <li>无命中返回空列表（由 Tool 转成拒答文案，不装成功）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagRetrievalAppServiceTest {

    @Mock
    private RagKnowledgeBaseRepository kbs;
    @Mock
    private RagChunkRepository chunks;
    @Mock
    private RagProvider provider;
    @Mock
    private EmbeddingClient embeddings;
    @Mock
    private RerankClient rerank;

    private RagRetrievalAppService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalAppService(kbs, chunks, provider, embeddings, rerank);
        lenient().when(embeddings.embedOne(anyLong(), anyString(), anyInt())).thenReturn(new float[]{0.1f, 0.2f});
        lenient().when(chunks.listByIds(any())).thenReturn(List.of());
    }

    /**
     * DENSE_ONLY：不调用 BM25，结果来自向量路。
     */
    @Test
    void denseOnlySkipsBm25() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "向量命中", 0.9d)));
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "退货政策", 5, null);

        assertEquals(1, hits.size());
        assertEquals("向量命中", hits.get(0).getContent());
        // 关键断言：能力不足时不查 BM25
        verify(provider, never()).searchBm25(any(), anyString(), anyInt());
    }

    /**
     * DENSE_BM25：两路都查，融合后同 key 合并为一条。
     */
    @Test
    void denseBm25FusesBothRecallPaths() {
        RagKnowledgeBase kb = kb("DENSE_BM25");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "向量命中A", 0.9d), hit(2L, "向量命中B", 0.8d)));
        when(provider.supportsBm25(any())).thenReturn(true);
        when(provider.searchBm25(any(), anyString(), anyInt()))
                .thenReturn(List.of(hit(1L, "BM25命中A", 3.2d), hit(3L, "BM25命中C", 2.1d)));

        List<SearchResult> hits = service.retrieve(kb, "退货政策", 5, null);

        verify(provider).searchBm25(any(), anyString(), anyInt());
        // 1 号被两路同时召回，应合并为一条而不是重复
        long c1 = hits.stream().filter(h -> h.getChunkId() == 1L).count();
        assertEquals(1, c1);
        assertTrue(hits.size() >= 2);
    }

    /**
     * BM25 能力在但检索返回空：直接退化为向量结果，不因融合而丢结果。
     */
    @Test
    void bm25EmptyFallsBackToVector() {
        RagKnowledgeBase kb = kb("DENSE_BM25");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "向量命中", 0.9d)));
        when(provider.supportsBm25(any())).thenReturn(true);
        when(provider.searchBm25(any(), anyString(), anyInt())).thenReturn(List.of());

        List<SearchResult> hits = service.retrieve(kb, "查询", 5, null);

        assertEquals(1, hits.size());
        assertEquals("向量命中", hits.get(0).getContent());
    }

    /**
     * topK 截断：入参 2 时最多返回 2 条。
     */
    @Test
    void truncatesToTopK() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        List<SearchResult> many = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(hit((long) i, "命中" + i, 1.0d - i * 0.01));
        }
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(many);
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "查询", 2, null);

        assertEquals(2, hits.size());
    }

    /**
     * 单条正文截断：超长内容必须被剪到上限内并带标记，避免挤爆上下文。
     */
    @Test
    void clipsLongContent() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        String longText = "甲".repeat(5000);
        RagChunk chunk = new RagChunk();
        chunk.setId(1L);
        chunk.setContent(longText);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "占位", 0.9d)));
        when(provider.supportsBm25(any())).thenReturn(false);
        when(chunks.listByIds(any())).thenReturn(List.of(chunk));

        List<SearchResult> hits = service.retrieve(kb, "查询", 5, null);

        String content = hits.get(0).getContent();
        assertTrue(content.length() <= RagRetrievalAppService.MAX_CONTENT_CHARS);
        assertTrue(content.endsWith("…(已截断)"), "截断必须带标记");
    }

    /**
     * 用 MySQL 完整正文覆盖 Milvus 的截断副本。
     */
    @Test
    void hydratesFullContentFromMysql() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        RagChunk chunk = new RagChunk();
        chunk.setId(1L);
        chunk.setContent("来自 MySQL 的完整正文");
        chunk.setChunkIndex(3);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "集合里的截断副本", 0.9d)));
        when(provider.supportsBm25(any())).thenReturn(false);
        when(chunks.listByIds(any())).thenReturn(List.of(chunk));

        List<SearchResult> hits = service.retrieve(kb, "查询", 5, null);

        assertEquals("来自 MySQL 的完整正文", hits.get(0).getContent());
        assertEquals(3, hits.get(0).getChunkIndex());
    }

    /**
     * 真零命中（向量空且 MySQL 也无切片）：返回空列表（Tool 转成 NO_HIT 原文案）。
     */
    @Test
    void returnsEmptyWhenNoHit() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenReturn(List.of());
        when(provider.supportsBm25(any())).thenReturn(false);
        when(chunks.countByKb(1L)).thenReturn(0);

        assertTrue(service.retrieve(kb, "无关问题", 5, null).isEmpty());
    }

    /**
     * 向量空但 MySQL 仍有切片：集合未同步/重建后未重入库，必须抛故障，禁止冒充 NO_HIT。
     */
    @Test
    void emptyVectorWithStoredChunksIsFault() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenReturn(List.of());
        when(provider.supportsBm25(any())).thenReturn(false);
        when(chunks.countByKb(1L)).thenReturn(5);

        RagStoreException ex = assertThrows(RagStoreException.class,
                () -> service.retrieve(kb, "退货流程", 5, null));
        assertTrue(ex.getMessage().contains("切片"));
        assertTrue(ex.getMessage().contains("重入库") || ex.getMessage().contains("未同步"));
    }

    /**
     * 总分阈值：有高分时低于阈值的被过滤。
     */
    @Test
    void filtersByScoreThreshold() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRetrievalConfig("{\"scoreThreshold\":0.8}");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "低分", 0.2d), hit(2L, "高分", 0.95d)));
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "查询", 5, null);

        assertEquals(1, hits.size());
        assertEquals("高分", hits.get(0).getContent());
    }

    /**
     * 阈值过严导致全灭：回退保留原召回，避免「有相关切片却误报无依据」。
     */
    @Test
    void thresholdWipeoutKeepsRawHits() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRetrievalConfig("{\"scoreThreshold\":0.99}");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "退货政策正文", 0.55d)));
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "退货流程", 5, null);

        assertEquals(1, hits.size());
        assertEquals("退货政策正文", hits.get(0).getContent());
    }

    /**
     * 多库检索：一个库失败不能让整次检索失败。
     */
    @Test
    void multiRetrieveSurvivesSingleKbFailure() {
        RagKnowledgeBase ok = kb("DENSE_ONLY");
        RagKnowledgeBase broken = kb("DENSE_ONLY");
        broken.setId(99L);
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenAnswer(inv -> {
            RagKnowledgeBase target = inv.getArgument(0);
            if (target.getId() == 99L) {
                throw new RagStoreException("向量库抖动");
            }
            return List.of(hit(1L, "好库命中", 0.9d));
        });
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieveMulti(List.of(broken, ok), "查询", 5, null);

        assertEquals(1, hits.size());
        assertEquals("好库命中", hits.get(0).getContent());
    }

    /**
     * 多库全部失败：必须抛出，不能返回空列表伪装成真零命中。
     */
    @Test
    void multiRetrieveAllFailuresPropagate() {
        RagKnowledgeBase a = kb("DENSE_ONLY");
        RagKnowledgeBase b = kb("DENSE_ONLY");
        b.setId(2L);
        when(embeddings.embedOne(anyLong(), anyString(), anyInt()))
                .thenThrow(new EmbeddingException("向量化不可用"));

        EmbeddingException ex = assertThrows(EmbeddingException.class,
                () -> service.retrieveMulti(List.of(a, b), "退货流程", 5, null));
        assertTrue(ex.getMessage().contains("向量化"));
    }

    /**
     * 空 query 直接返回空，不打向量化。
     */
    @Test
    void blankQueryReturnsEmptyWithoutEmbedding() {
        assertTrue(service.retrieve(kb("DENSE_ONLY"), "   ", 5, null).isEmpty());
        verify(embeddings, never()).embedOne(anyLong(), anyString(), anyInt());
    }

    /**
     * 不可用知识库的拒绝原因可读（供 Tool 与接口直接回显）。
     */
    @Test
    void unusableReasonForInitFailed() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setStatus("INIT_FAILED");
        String reason = service.unusableReason(kb);
        assertTrue(reason != null && reason.contains("初始化失败"));
    }

    /**
     * 可用知识库没有拒绝原因。
     */
    @Test
    void usableKbHasNoReason() {
        assertEquals(null, service.unusableReason(kb("DENSE_ONLY")));
    }

    /**
     * 截断工具函数：短文本原样返回。
     */
    @Test
    void clipKeepsShortText() {
        assertEquals("短文本", RagRetrievalAppService.clipText("短文本", 100));
        assertFalse(RagRetrievalAppService.clipText("短文本", 100).contains("已截断"));
    }

    /**
     * Agent「知识库」Tab 配了重排：即使用知识库自身关着重排，也按 Agent 的模型精排。
     * 这条保证「配置入口唯一在知识库 Tab」之后，Agent 侧的开关真的会生效。
     */
    @Test
    void agentRerankOverridesKbRerank() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRerankModelId(55L);
        kb.setRetrievalConfig("{\"rerankEnabled\":false}");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "候选正文", 0.9d)));
        when(provider.supportsBm25(any())).thenReturn(false);
        when(rerank.rerank(eq(77L), anyString(), any(), anyInt()))
                .thenReturn(List.of(new RerankHit(0, 0.42d)));

        List<SearchResult> hits = service.retrieve(kb, "查询", 5, null, 77L);

        // 用的是 Agent 的实例 77，不是知识库的 55
        verify(rerank).rerank(eq(77L), anyString(), any(), anyInt());
        assertEquals(0.42d, hits.get(0).getRerankScore(), 1e-9);
    }

    /**
     * 没配 Agent 重排且知识库关着重排：完全不调精排，避免无意义出站。
     */
    @Test
    void noRerankWhenNeitherSideEnabled() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRetrievalConfig("{\"rerankEnabled\":false}");
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "候选正文", 0.9d)));
        when(provider.supportsBm25(any())).thenReturn(false);

        service.retrieve(kb, "查询", 5, null, null);

        verify(rerank, never()).rerank(any(), anyString(), any(), anyInt());
    }

    /**
     * 多库 + Agent 级重排：精排只出站一次（合并后统一做），不是每库一次。
     * 同时验证重排用的是 Agent 配的实例，而不是各库自己的。
     */
    @Test
    void multiKbReranksOnceWithAgentModel() {
        RagKnowledgeBase a = kb("DENSE_ONLY");
        RagKnowledgeBase b = kb("DENSE_ONLY");
        b.setId(2L);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    RagKnowledgeBase target = inv.getArgument(0);
                    return List.of(hit(target.getId() * 10, "正文-" + target.getId(), 0.9d));
                });
        when(provider.supportsBm25(any())).thenReturn(false);
        when(rerank.rerank(eq(77L), anyString(), any(), anyInt()))
                .thenReturn(List.of(new RerankHit(1, 0.9d), new RerankHit(0, 0.3d)));

        List<SearchResult> hits = service.retrieveMulti(List.of(a, b), "查询", 5, null, 77L);

        // 两次库检索 + 一次合并后精排
        verify(rerank, org.mockito.Mockito.times(1)).rerank(eq(77L), anyString(), any(), anyInt());
        assertEquals(2, hits.size());
        // 精排返回的顺序原样生效：先 1 号位再由 0 号位
        assertEquals(0.9d, hits.get(0).getRerankScore(), 1e-9);
        assertEquals(0.3d, hits.get(1).getRerankScore(), 1e-9);
    }

    /**
     * 单篇文档限段：同一 docId 在单库结果里最多留 maxChunksPerDoc 段，不许一篇吃光 topK。
     */
    @Test
    void capsChunksPerDocWithinSingleKb() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRetrievalConfig("{\"topK\":5,\"scoreThreshold\":0,\"maxChunksPerDoc\":2}");
        List<SearchResult> many = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            SearchResult hit = hit((long) i, "同篇第" + i + "段", 1.0d - i * 0.01);
            // 全部落在同一篇文档上
            hit.setDocId(100L);
            many.add(hit);
        }
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenReturn(many);
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "查询", 0, null);

        assertEquals(2, hits.size(), "同一文档只保留 maxChunksPerDoc 段");
        assertTrue(hits.stream().allMatch(h -> h.getDocId() == 100L));
    }

    /**
     * 限段只作用于同一文档：不同文档仍能占满各自名额。
     */
    @Test
    void capPerDocKeepsDifferentDocuments() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRetrievalConfig("{\"topK\":5,\"scoreThreshold\":0,\"maxChunksPerDoc\":1}");
        List<SearchResult> many = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            SearchResult hit = hit((long) i, "文档" + i + "正文", 1.0d - i * 0.01);
            hit.setDocId((long) i);
            many.add(hit);
        }
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenReturn(many);
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "查询", 0, null);

        assertEquals(4, hits.size(), "每篇留 1 段，四篇都在");
    }

    /**
     * 限段必须先于 topK 截断：一篇文档的 10 段不能占满 topK=5，
     * 否则排在后面的其它文档永远没有机会进结果。
     */
    @Test
    void capRunsBeforeTopKSoOtherDocsStillFit() {
        RagKnowledgeBase kb = kb("DENSE_ONLY");
        kb.setRetrievalConfig("{\"topK\":5,\"scoreThreshold\":0,\"maxChunksPerDoc\":2}");
        List<SearchResult> many = new ArrayList<>();
        // 前 10 条全属同一篇（分数最高），后面才是另一篇
        for (int i = 1; i <= 10; i++) {
            SearchResult hit = hit((long) i, "长文第" + i + "段", 1.0d - i * 0.01);
            hit.setDocId(100L);
            many.add(hit);
        }
        SearchResult other = hit(11L, "另一篇正文", 0.50d);
        other.setDocId(200L);
        many.add(other);
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenReturn(many);
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieve(kb, "查询", 0, null);

        // 100 号只留 2 段，200 号能排进来；总共不超过 topK=5
        assertEquals(3, hits.size());
        assertEquals(2, hits.stream().filter(h -> h.getDocId() == 100L).count());
        assertEquals(1, hits.stream().filter(h -> h.getDocId() == 200L).count());
    }

    /**
     * 多库合并后按条数截：配置了 mergedTopK 就以它为准，不再只靠 6000 字反推。
     */
    @Test
    void mergedCountUsesConfiguredTopK() {
        RagKnowledgeBase a = kb("DENSE_ONLY");
        a.setRetrievalConfig("{\"topK\":5,\"scoreThreshold\":0,\"mergedTopK\":3}");
        RagKnowledgeBase b = kb("DENSE_ONLY");
        b.setId(2L);
        b.setRetrievalConfig("{\"topK\":5,\"scoreThreshold\":0,\"mergedTopK\":3}");
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenAnswer(inv -> {
            RagKnowledgeBase target = inv.getArgument(0);
            List<SearchResult> out = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                SearchResult hit = hit(target.getId() * 100 + i, "库" + target.getId() + "第" + i + "段",
                        1.0d - i * 0.01);
                hit.setKbId(target.getId());
                hit.setDocId(target.getId() * 100 + i);
                out.add(hit);
            }
            return out;
        });
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieveMulti(List.of(a, b), "查询", 0, null);

        assertEquals(3, hits.size(), "合并后按 mergedTopK=3 截断");
    }

    /**
     * 未配 mergedTopK：用各库生效 topK 之和作上限（5+5=10），不是无上限。
     */
    @Test
    void mergedCountFallsBackToSumOfTopK() {
        RagKnowledgeBase a = kb("DENSE_ONLY");
        RagKnowledgeBase b = kb("DENSE_ONLY");
        b.setId(2L);
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenAnswer(inv -> {
            RagKnowledgeBase target = inv.getArgument(0);
            List<SearchResult> out = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                SearchResult hit = hit(target.getId() * 100 + i, "正文", 1.0d - i * 0.01);
                hit.setKbId(target.getId());
                hit.setDocId(target.getId() * 100 + i);
                out.add(hit);
            }
            return out;
        });
        when(provider.supportsBm25(any())).thenReturn(false);

        List<SearchResult> hits = service.retrieveMulti(List.of(a, b), "查询", 0, null);

        // 每库 topK=5 → 合计上限 10；两库各 8 条候选被各自截到 5 后再合并为 10
        assertEquals(10, hits.size());
    }

    private RagKnowledgeBase kb(String capability) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(0L);
        kb.setName("测试库");
        kb.setStatus("READY");
        kb.setVisibility("PRIVATE");
        kb.setIsolationMode("PHYSICAL");
        kb.setCollectionName("agenthub_kb_1");
        kb.setStoreCapability(capability);
        kb.setEmbeddingModelId(8L);
        kb.setVectorDimension(2);
        kb.setCreatedBy(9L);
        kb.setRetrievalConfig("{\"topK\":5,\"scoreThreshold\":0}");
        return kb;
    }

    private SearchResult hit(Long chunkId, String content, double score) {
        SearchResult hit = new SearchResult();
        hit.setChunkId(chunkId);
        hit.setDocId(100L);
        hit.setKbId(1L);
        hit.setContent(content);
        hit.setTitle("a.txt");
        hit.setSource("a.txt");
        hit.setVectorScore(score);
        hit.setHitBy("vector");
        return hit;
    }
}
