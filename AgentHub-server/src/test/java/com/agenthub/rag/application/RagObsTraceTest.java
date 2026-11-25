package com.agenthub.rag.application;

import com.agenthub.obs.domain.ObsToolSpanContext;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.RagQueryRewriter;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankHit;
import com.agenthub.spi.rag.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索的 Trace 子步。所属域：rag 的 application。
 * <p>
 * 用内存 recorder 代替落库，真实跑 {@link RagSearchTool} → {@link RagRetrievalAppService} 链路，
 * 断言：
 * <ol>
 *   <li>子步顺序固定为 tool.input → rewrite → retrieve.vector → retrieve.bm25 → rrf → rerank → tool.output；</li>
 *   <li>所有子步 parent 都是本次 TOOL span；</li>
 *   <li>未开改写 / 未开 rerank / DENSE_ONLY 时对应步骤 skip 且带原因；</li>
 *   <li>召回片段写 chunkId / 分数，tool.output 与工具返回一致。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagObsTraceTest {

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
    @Mock
    private AgentKbBindAppService binds;
    @Mock
    private AgentRerankLookup rerankLookup;

    private RagSearchTool tool;
    private RecordingRecorder recorder;
    private ObsTraceScope scope;
    private Long toolSpanId;

    @BeforeEach
    void setUp() {
        RagRetrievalAppService retrieval = new RagRetrievalAppService(kbs, chunks, provider, embeddings, rerank,
                rewriter);
        tool = new RagSearchTool(retrieval, binds, rerankLookup, new ObjectMapper());

        recorder = new RecordingRecorder();
        scope = new ObsTraceScope("trace-rag-obs", recorder);
        toolSpanId = scope.open(SpanType.TOOL, "searchKnowledgeBase", 7L, null, null);
        scope.registerToolSpan("call-1", toolSpanId);
        ObsToolSpanContext.bind(scope, toolSpanId, 7L);
        RagToolContext.bind(7L);

        lenient().when(embeddings.embedOne(anyLong(), anyString(), anyInt())).thenReturn(new float[]{0.1f, 0.2f});
        lenient().when(chunks.listByIds(any())).thenReturn(List.of());
        lenient().when(binds.overridesFor(7L)).thenReturn(Map.of());
        lenient().when(rerankLookup.enabledRerankModelId(7L)).thenReturn(Optional.empty());
        // 默认「改写未执行」：各用例按需覆盖成成功 / 失败回退
        lenient().when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.skipped("", "rewrite_disabled"));
    }

    @AfterEach
    void tearDown() {
        RagToolContext.clear();
        ObsToolSpanContext.clear();
    }

    /**
     * 完整链路：双路召回 + RRF + Agent 级重排，七步齐全且顺序稳定。
     */
    @Test
    void writesSevenStepsInOrder() {
        RagKnowledgeBase kb = kb("DENSE_BM25", "{\"topK\":5,\"rrfEnabled\":true,\"rerankEnabled\":false}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(rerankLookup.enabledRerankModelId(7L)).thenReturn(Optional.of(77L));
        when(provider.supportsBm25(kb)).thenReturn(true);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "向量命中A", 0.9d), hit(2L, "向量命中B", 0.8d)));
        when(provider.searchBm25(any(), anyString(), anyInt()))
                .thenReturn(List.of(bm25Hit(1L, "BM25命中A", 3.2d), bm25Hit(3L, "BM25命中C", 2.1d)));
        when(rerank.rerank(eq(77L), anyString(), any(), anyInt()))
                .thenReturn(List.of(new RerankHit(0, 0.95d), new RerankHit(1, 0.6d)));

        String out = tool.searchKnowledgeBase("退货流程", 3);

        assertFalse(out.contains(RagSearchTool.NO_HIT));
        List<SpanDraft> steps = stepsUnderTool();
        assertEquals(List.of(
                RagObsSpans.TOOL_INPUT,
                RagObsSpans.SELECT_KB,
                RagObsSpans.REWRITE,
                RagObsSpans.RETRIEVE_VECTOR,
                RagObsSpans.RETRIEVE_BM25,
                RagObsSpans.RRF,
                RagObsSpans.RERANK,
                RagObsSpans.TOOL_OUTPUT), steps.stream().map(SpanDraft::name).toList());
        for (SpanDraft step : steps) {
            assertEquals(SpanType.RETRIEVE, step.type());
            assertEquals(toolSpanId, step.parentId(), "子步必须挂本次 TOOL span");
        }

        // 向量 / BM25 明细要带片段 id 与分数，供泳道排障
        String vectorOut = closeOf(RagObsSpans.RETRIEVE_VECTOR).getOutputText();
        assertTrue(vectorOut.contains("chunkId=1"));
        assertTrue(vectorOut.contains("vector=0.9000"));
        String bm25Out = closeOf(RagObsSpans.RETRIEVE_BM25).getOutputText();
        assertTrue(bm25Out.contains("chunkId=3"));
        assertTrue(bm25Out.contains("bm25=2.1000"));
        // RRF 之后应有融合分
        assertTrue(closeOf(RagObsSpans.RRF).getOutputText().contains("rrf="));
        // 重排分与最终片段
        assertTrue(closeOf(RagObsSpans.RERANK).getOutputText().contains("rerank=0.9500"));

        SpanClose output = closeOf(RagObsSpans.TOOL_OUTPUT);
        assertEquals(ObsStatus.OK, output.getStatus());
        assertTrue(output.getAttrsJson().contains("\"outcome\":\"ok\""));
        assertNotNull(output.getRetrieveQuery());
    }

    /**
     * 关改写 / 关双路 / 关 rerank：对应步骤 skip 并写清原因。
     */
    @Test
    void skippedStepsCarryReason() {
        RagKnowledgeBase kb = kb("DENSE_ONLY", "{\"topK\":5,\"rrfEnabled\":false,\"rerankEnabled\":false}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(provider.supportsBm25(kb)).thenReturn(false);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "唯一命中", 0.9d)));

        tool.searchKnowledgeBase("退货流程", 5);

        assertTrue(closeOf(RagObsSpans.REWRITE).getAttrsJson().contains("\"skipReason\":\"rewrite_disabled\""));
        assertTrue(closeOf(RagObsSpans.RETRIEVE_BM25).getAttrsJson().contains("\"skipReason\":\"rrf_disabled\""));
        assertTrue(closeOf(RagObsSpans.RRF).getAttrsJson().contains("\"skipReason\":\"rrf_disabled\""));
        assertTrue(closeOf(RagObsSpans.RERANK).getAttrsJson().contains("\"skipReason\":\"rerank_disabled\""));
    }

    /**
     * 开了改写但 REWRITE 槽没有可用模型：原因要与「未开启」区分，避免误判成配置没开。
     * <p>
     * 这是本刀之后的第二道门：开关只是第一道，槽空时不改写、不改问句、检索照常走。
     */
    @Test
    void rewriteEnabledButNoRewriteModel() {
        RagKnowledgeBase kb = kb("DENSE_ONLY", "{\"topK\":5,\"rewriteEnabled\":true}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(provider.supportsBm25(kb)).thenReturn(false);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "命中", 0.9d)));
        // 端口返回「跳过：没有 REWRITE 模型」，模拟槽为空
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.skipped("退货流程", "rewrite_model_missing"));

        tool.searchKnowledgeBase("退货流程", 5);

        SpanClose rewrite = closeOf(RagObsSpans.REWRITE);
        assertTrue(rewrite.getAttrsJson().contains("\"skipReason\":\"rewrite_model_missing\""));
        assertTrue(rewrite.getAttrsJson().contains("\"applied\":false"));
        // 没改写 → 召回仍用原问句
        verify(provider).searchVector(any(), eq("退货流程"), anyInt(), any());
    }

    /**
     * 改写成功：rewrite 子步状态 OK 且 applied=true，召回用的是改写句而不是原问。
     */
    @Test
    void rewriteAppliedUsesRewrittenQueryForRecall() {
        RagKnowledgeBase kb = kb("DENSE_ONLY", "{\"topK\":5,\"rewriteEnabled\":true}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(provider.supportsBm25(kb)).thenReturn(false);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "命中", 0.9d)));
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.applied("退货政策 七天无理由", 88L));

        tool.searchKnowledgeBase("请问一下那个退货怎么弄", 5);

        SpanClose rewrite = closeOf(RagObsSpans.REWRITE);
        assertEquals(ObsStatus.OK, rewrite.getStatus());
        assertTrue(rewrite.getAttrsJson().contains("\"applied\":true"));
        assertTrue(rewrite.getAttrsJson().contains("\"modelInstanceId\":88"));
        // 选库仍用原问；召回用改写句
        assertEquals("退货政策 七天无理由", rewrite.getOutputText());
        verify(provider).searchVector(any(), eq("退货政策 七天无理由"), anyInt(), any());
    }

    /**
     * 改写失败：rewrite 子步记回退原因，检索仍用原问句出结果，不把整次检索打失败。
     */
    @Test
    void rewriteFailureFallsBackToOriginalQuery() {
        RagKnowledgeBase kb = kb("DENSE_ONLY", "{\"topK\":5,\"rewriteEnabled\":true}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(provider.supportsBm25(kb)).thenReturn(false);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "命中", 0.9d)));
        when(rewriter.rewrite(any(), anyString()))
                .thenReturn(RagQueryRewriter.Result.failed("退货流程", "rewrite_timeout",
                        "BizException", 88L));

        String out = tool.searchKnowledgeBase("退货流程", 5);

        assertFalse(out.contains(RagSearchTool.NO_HIT), "改写失败不能让检索变成无命中");
        SpanClose rewrite = closeOf(RagObsSpans.REWRITE);
        assertTrue(rewrite.getAttrsJson().contains("\"skipReason\":\"rewrite_timeout\""));
        assertTrue(rewrite.getAttrsJson().contains("\"failed\":true"));
        assertTrue(rewrite.getAttrsJson().contains("\"fallback\":true"));
        verify(provider).searchVector(any(), eq("退货流程"), anyInt(), any());
    }

    /**
     * 真零命中：tool.output 仍要写实际返回文案，并标 no_hit。
     */
    @Test
    void recordsNoHitOutput() {
        RagKnowledgeBase kb = kb("DENSE_ONLY", "{\"topK\":5}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(provider.supportsBm25(kb)).thenReturn(false);
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenReturn(List.of());
        when(chunks.countByKb(1L)).thenReturn(0);

        String out = tool.searchKnowledgeBase("无关问题", 5);

        assertEquals(RagSearchTool.NO_HIT, out);
        SpanClose output = closeOf(RagObsSpans.TOOL_OUTPUT);
        assertEquals(RagSearchTool.NO_HIT, output.getOutputText());
        assertTrue(output.getAttrsJson().contains("\"outcome\":\"no_hit\""));
    }

    /**
     * 没有 trace 锚点：检索照常返回，且不允许因为埋点失败抛错。
     */
    @Test
    void worksWithoutTraceAnchor() {
        ObsToolSpanContext.clear();
        RagKnowledgeBase kb = kb("DENSE_ONLY", "{\"topK\":5}");
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb));
        when(provider.supportsBm25(kb)).thenReturn(false);
        when(provider.searchVector(any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit(1L, "命中", 0.9d)));

        String out = tool.searchKnowledgeBase("退货流程", 5);

        assertFalse(out.isBlank());
        assertEquals(1, recorder.drafts.size(), "只剩测试自己打开的 TOOL span，不应再写检索子步");
    }

    private List<SpanDraft> stepsUnderTool() {
        List<SpanDraft> out = new ArrayList<>();
        for (int i = 0; i < recorder.drafts.size(); i++) {
            SpanDraft draft = recorder.drafts.get(i);
            if (draft.type() == SpanType.RETRIEVE && toolSpanId.equals(draft.parentId())) {
                out.add(draft);
            }
        }
        return out;
    }

    private SpanClose closeOf(String name) {
        for (int i = 0; i < recorder.drafts.size(); i++) {
            SpanDraft draft = recorder.drafts.get(i);
            if (name.equals(draft.name())) {
                SpanClose close = recorder.closes.get(recorder.ids.get(i));
                assertNotNull(close, "子步未收尾：" + name);
                return close;
            }
        }
        throw new AssertionError("未找到子步：" + name);
    }

    private RagKnowledgeBase kb(String capability, String retrievalConfig) {
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
        kb.setRetrievalConfig(retrievalConfig);
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

    private SearchResult bm25Hit(Long chunkId, String content, double score) {
        SearchResult hit = hit(chunkId, content, score);
        hit.setVectorScore(null);
        hit.setBm25Score(score);
        hit.setHitBy("bm25");
        return hit;
    }

    /**
     * 内存 recorder：只记录开放/收尾，不落库，供断言父子与 attrs。
     */
    private static final class RecordingRecorder implements TraceRecorder {

        private final List<SpanDraft> drafts = new ArrayList<>();
        private final List<Long> ids = new ArrayList<>();
        private final Map<Long, SpanClose> closes = new LinkedHashMap<>();
        private long serial = 100L;

        @Override
        public Optional<ObsTraceScope> beginConsole(TraceStart start) {
            return Optional.empty();
        }

        @Override
        public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
            long id = ++serial;
            drafts.add(draft);
            ids.add(id);
            return id;
        }

        @Override
        public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
            closes.put(spanId, close);
        }

        @Override
        public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
        }

        @Override
        public void cancelSession(long sessionId, String reason) {
        }
    }
}
