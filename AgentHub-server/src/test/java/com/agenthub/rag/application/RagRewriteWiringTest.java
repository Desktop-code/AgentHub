package com.agenthub.rag.application;

import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.RagQueryRewriter;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索链路上的查询改写接线。所属域：rag 的 application。
 * <p>
 * 全部替身，<b>零真实出站</b>。覆盖本刀的硬约束：
 * <ol>
 *   <li><b>两道门</b>：库里开关为关 → 根本不调改写模型；开关开但槽空 → 端口返回跳过，仍用原问；</li>
 *   <li><b>一次调用只改写一次</b>：多库共享同一句改写，不按库各改一遍；</li>
 *   <li><b>开关不一致</b>：开的库用改写句召回，关的库仍用原问句；</li>
 *   <li><b>失败回退</b>：改写失败时检索照常出结果，用的是原问句；</li>
 *   <li><b>选库用原问</b>：改写不参与选库；</li>
 *   <li><b>不做双路融合</b>：改写成功后不会再用原问多查一遍。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagRewriteWiringTest {

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
    @Mock
    private RagQueryRewriter rewriter;

    private RagRetrievalAppService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalAppService(kbs, chunks, provider, embeddings, rerank, rewriter);
        lenient().when(embeddings.embedOne(anyLong(), anyString(), anyInt())).thenReturn(new float[]{0.1f, 0.2f});
        lenient().when(chunks.listByIds(any())).thenReturn(List.of());
        lenient().when(chunks.countByKb(anyLong())).thenReturn(0);
        lenient().when(provider.supportsBm25(any())).thenReturn(false);
        lenient().when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> List.of(hit(inv.getArgument(0))));
        // 工具线程身份：改写要按 Agent 记账
        RagToolContext.bind(7L, 21L, 99L);
    }

    @AfterEach
    void tearDown() {
        RagToolContext.clear();
    }

    /**
     * 两道门之第一道：所有入选库开关都关 → 连改写模型都不调，召回用原问。
     */
    @Test
    void rewriteDisabledNeverCallsModel() {
        RagKnowledgeBase kb = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":false}");

        service.retrieveMulti(List.of(kb), "退货怎么弄", 0, Map.of(), null);

        verify(rewriter, never()).rewrite(any(), anyString());
        verify(provider).searchVector(any(), eq("退货怎么弄"), anyInt(), any());
    }

    /**
     * 两道门之第二道：开关开，但端口说「没有 REWRITE 模型」→ 按原问检索。
     */
    @Test
    void rewriteEnabledButNoModelKeepsOriginal() {
        RagKnowledgeBase kb = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.skipped("退货怎么弄", "rewrite_model_missing"));

        service.retrieveMulti(List.of(kb), "退货怎么弄", 0, Map.of(), null);

        verify(provider).searchVector(any(), eq("退货怎么弄"), anyInt(), any());
        verify(provider, never()).searchVector(any(), eq("七天无理由退货"), anyInt(), any());
    }

    /**
     * 两道门都开：召回用改写句，且改写只调一次（不是每库一次）。
     */
    @Test
    void bothGatesOpenUsesRewrittenQueryOnceForAllKbs() {
        RagKnowledgeBase a = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        RagKnowledgeBase b = kb(2L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.applied("七天无理由退货", 88L));

        List<SearchResult> hits = service.retrieveMulti(List.of(a, b), "那个退货咋弄", 0, Map.of(), null);

        assertTrue(hits.size() >= 1);
        // 一次调用最多改写一次：两个库共用同一句
        verify(rewriter, times(1)).rewrite(any(), anyString());
        verify(provider, times(2)).searchVector(any(), eq("七天无理由退货"), anyInt(), any());
        // 不做双路融合：原问不会再多查一遍
        verify(provider, never()).searchVector(any(), eq("那个退货咋弄"), anyInt(), any());
    }

    /**
     * 开关不一致：开的库用改写句，关的库仍用原问句。
     */
    @Test
    void mixedSwitchesRoutePerKb() {
        RagKnowledgeBase on = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        RagKnowledgeBase off = kb(2L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":false}");
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.applied("七天无理由退货", 88L));

        service.retrieveMulti(List.of(on, off), "那个退货咋弄", 0, Map.of(), null);

        verify(rewriter, times(1)).rewrite(any(), anyString());
        // 两个库都选了（单库兜底/描述匹配都不参与这里），一句改写 + 一句原问各查一次
        verify(provider).searchVector(eq(on), eq("七天无理由退货"), anyInt(), any());
        verify(provider).searchVector(eq(off), eq("那个退货咋弄"), anyInt(), any());
    }

    /**
     * 绑定覆盖项优先于库配置：覆盖项关掉时即使库开着也不改写。
     */
    @Test
    void bindOverrideDisablesRewrite() {
        RagKnowledgeBase kb = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");

        service.retrieveMulti(List.of(kb), "退货怎么弄", 0, Map.of(1L, "{\"rewriteEnabled\":false}"), null);

        verify(rewriter, never()).rewrite(any(), anyString());
        verify(provider).searchVector(any(), eq("退货怎么弄"), anyInt(), any());
    }

    /**
     * 绑定覆盖项开启：库关着也能被覆盖打开。
     */
    @Test
    void bindOverrideEnablesRewrite() {
        RagKnowledgeBase kb = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":false}");
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.applied("七天无理由退货", 88L));

        service.retrieveMulti(List.of(kb), "那个退货咋弄", 0, Map.of(1L, "{\"rewriteEnabled\":true}"), null);

        verify(provider).searchVector(any(), eq("七天无理由退货"), anyInt(), any());
    }

    /**
     * 改写失败：检索照常出结果，用的是原问句，不把整次检索打失败。
     */
    @Test
    void rewriteFailureStillReturnsHitsWithOriginalQuery() {
        RagKnowledgeBase kb = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        when(rewriter.rewrite(any(), anyString())).thenReturn(RagQueryRewriter.Result.failed(
                "退货怎么弄", "rewrite_timeout", "RuntimeException", 88L));

        List<SearchResult> hits = service.retrieveMulti(List.of(kb), "退货怎么弄", 0, Map.of(), null);

        assertEquals(1, hits.size());
        verify(provider).searchVector(any(), eq("退货怎么弄"), anyInt(), any());
    }

    /**
     * 选库用原问句：改写句里的词不会反过来影响「查哪些库」。
     * <p>
     * 这里用两库 + 只有 B 的描述匹配原问来制造差异：若选库用了改写句就可能选错库。
     */
    @Test
    void selectionUsesOriginalQueryNotRewrite() {
        RagKnowledgeBase refund = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        refund.setDescription("退款到账时间与进度");
        RagKnowledgeBase travel = kb(2L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        travel.setDescription("差旅报销标准");
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.applied("差旅报销标准", 88L));

        service.retrieveMulti(List.of(refund, travel), "退款到账要多久", 0, Map.of(), null);

        // 选库按原问命中「退款」库；改写句里的「差旅」不应把检索引到差旅库
        verify(provider).searchVector(eq(refund), eq("差旅报销标准"), anyInt(), any());
        verify(provider, never()).searchVector(eq(travel), anyString(), anyInt(), any());
    }

    /**
     * 单库入口：开关关时不调改写；开关开时用改写句。
     */
    @Test
    void singleKbRespectsSwitch() {
        RagKnowledgeBase off = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":false}");
        service.retrieve(off, "退货怎么弄", 0, null);
        verify(rewriter, never()).rewrite(any(), anyString());

        RagKnowledgeBase on = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.applied("七天无理由退货", 88L));
        service.retrieve(on, "那个退货咋弄", 0, null);

        verify(provider).searchVector(any(), eq("七天无理由退货"), anyInt(), any());
    }

    /**
     * 工具线程没有 Agent：不调改写，按原问检索（宁可少这一步，也不跨身份改写）。
     */
    @Test
    void missingAgentContextSkipsRewrite() {
        RagToolContext.clear();
        RagKnowledgeBase kb = kb(1L, "{\"topK\":5,\"scoreThreshold\":0,\"rewriteEnabled\":true}");

        service.retrieveMulti(List.of(kb), "退货怎么弄", 0, Map.of(), null);

        verify(rewriter, never()).rewrite(any(), anyString());
        verify(provider).searchVector(any(), eq("退货怎么弄"), anyInt(), any());
    }

    private static RagKnowledgeBase kb(Long id, String retrievalConfig) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(id);
        kb.setTenantId(0L);
        kb.setName("库" + id);
        kb.setStatus("READY");
        kb.setVisibility("PRIVATE");
        kb.setIsolationMode("PHYSICAL");
        kb.setCollectionName("agenthub_kb_" + id);
        kb.setStoreCapability("DENSE_ONLY");
        kb.setEmbeddingModelId(8L);
        kb.setVectorDimension(2);
        kb.setRetrievalConfig(retrievalConfig);
        return kb;
    }

    private static SearchResult hit(RagKnowledgeBase kb) {
        SearchResult hit = new SearchResult();
        hit.setChunkId(kb.getId());
        hit.setDocId(kb.getId() * 100);
        hit.setKbId(kb.getId());
        hit.setContent("库" + kb.getId() + "的正文");
        hit.setTitle("a.txt");
        hit.setSource("a.txt");
        hit.setVectorScore(0.9d);
        hit.setHitBy("vector");
        return hit;
    }
}
