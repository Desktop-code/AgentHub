package com.agenthub.rag.application;

import com.agenthub.common.text.UserFacingText;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.spi.rag.EmbeddingException;
import com.agenthub.spi.rag.RagStoreException;
import com.agenthub.spi.rag.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 检索工具的对外契约与失败语义。所属域：rag 的 application。③④ 期验收主用例。
 * <p>
 * 覆盖点：
 * <ol>
 *   <li>schema 契约：{@code @Tool(name)} 与 tool_item.name 一致；参数只有 query / topK，
 *       **不含 kbId / tenantId**（否则模型能越权指定库）；</li>
 *   <li>无 Agent 上下文 → 明确拒绝，不查；</li>
 *   <li>无可用绑定 → 返回 NO_BINDING；</li>
 *   <li>无命中 → 返回 NO_HIT（不是空数组）；</li>
 *   <li>向量化/向量库故障 → 只回 KB_UNAVAILABLE 友好句，且不含路径/端口/异常码；</li>
 *   <li>正常 → 返回 JSON 数组且字段可控。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagSearchToolTest {

    @Mock
    private RagRetrievalAppService retrieval;
    @Mock
    private AgentKbBindAppService binds;
    @Mock
    private AgentRerankLookup rerankLookup;

    private RagSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new RagSearchTool(retrieval, binds, rerankLookup, new ObjectMapper());
        lenient().when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any())).thenReturn(List.of());
        lenient().when(rerankLookup.enabledRerankModelId(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /**
     * 用完必须清上下文，避免影响同批其它用例。
     */
    @AfterEach
    void tearDown() {
        RagToolContext.clear();
    }

    /**
     * 工具名必须与种子 tool_item.name 一致，否则装配时找不到实现。
     */
    @Test
    void toolNameMatchesSeed() throws Exception {
        Method method = RagSearchTool.class.getMethod("searchKnowledgeBase", String.class, Integer.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertNotNull(annotation, "必须用 @Tool 注解，AgentScope 只扫描注解方法");
        assertEquals("searchKnowledgeBase", annotation.name());
        assertEquals("searchKnowledgeBase", RagSearchTool.toolName());
        assertFalse(annotation.description().isBlank(), "描述必须给模型看");
    }

    /**
     * 参数只有 query 与 topK —— 不能出现 kbId / tenantId，否则模型可越权指定知识库。
     */
    @Test
    void parametersExposeNoKbOrTenant() throws Exception {
        Method method = RagSearchTool.class.getMethod("searchKnowledgeBase", String.class, Integer.class);
        List<String> names = new java.util.ArrayList<>();
        for (Parameter p : method.getParameters()) {
            io.agentscope.core.tool.ToolParam tp = p.getAnnotation(io.agentscope.core.tool.ToolParam.class);
            if (tp != null) {
                names.add(tp.name());
            }
        }
        assertEquals(List.of("query", "topK"), names);
        assertFalse(names.contains("kbId"));
        assertFalse(names.contains("tenantId"));
    }

    /**
     * 没有 Agent 上下文：拒绝并提示未开通，且完全不查。
     */
    @Test
    void rejectsWithoutAgentContext() {
        String out = tool.searchKnowledgeBase("退货政策", 5);

        assertEquals(RagSearchTool.NO_BINDING, out);
        verify(retrieval, never()).retrieveMulti(any(), anyString(), anyInt(), any(), any());
    }

    /**
     * 有上下文但没绑任何库：同样明确拒绝（验收：无绑定 → 原文案）。
     */
    @Test
    void rejectsWithoutBindings() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of());

        assertEquals(RagSearchTool.NO_BINDING, tool.searchKnowledgeBase("退货政策", 5));
        verify(retrieval, never()).retrieveMulti(any(), anyString(), anyInt(), any(), any());
    }

    /**
     * 有绑定且可用：走检索（验收：有绑定 → 可检索路径），不返回「未绑定」文案。
     */
    @Test
    void retrievesWhenBindingsUsable() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(binds.overridesFor(7L)).thenReturn(Map.of());
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any())).thenReturn(List.of(hit()));

        String out = tool.searchKnowledgeBase("退货政策", 5);

        assertFalse(out.equals(RagSearchTool.NO_BINDING));
        verify(retrieval).retrieveMulti(any(), anyString(), anyInt(), any(), any());
    }

    /**
     * 正常链路：绑定可用 → 调检索 → 返回 JSON（有命中 mock → 非 NO_HIT）。
     */
    @Test
    void returnsJsonWhenHitsFound() throws Exception {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(binds.overridesFor(7L)).thenReturn(Map.of(1L, "{\"topK\":3}"));
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any())).thenReturn(List.of(hit()));

        String out = tool.searchKnowledgeBase("退货流程", 5);

        assertFalse(out.contains(RagSearchTool.NO_HIT));
        assertFalse(out.equals(RagSearchTool.NO_BINDING));
        JsonNode node = new ObjectMapper().readTree(out);
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("七天无理由退货需商品完好", node.get(0).get("content").asText());
        assertEquals("a.txt", node.get(0).get("title").asText());
        // 只给综合分，不把 vector/bm25/rrf 四路分数一起丢给模型
        assertTrue(node.get(0).has("score"));
        assertFalse(node.get(0).has("vectorScore"));
    }

    /**
     * 真零命中：返回固定拒答原文案，而不是空数组（空数组会让模型自由发挥）。
     */
    @Test
    void returnsNoHitMessage() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any())).thenReturn(List.of());

        assertEquals(RagSearchTool.NO_HIT, tool.searchKnowledgeBase("无关问题", 5));
    }

    /**
     * 检索层把「集合未同步」抛成 RagStoreException 时：故障文案，绝不能是「未找到相关内容」。
     */
    @Test
    void reportsUnsyncedStoreAsFailureNotNoHit() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any()))
                .thenThrow(new RagStoreException("向量库无命中但知识库仍有 5 条切片（可能集合未同步或重建后未重入库）"));

        String out = tool.searchKnowledgeBase("退货流程", 5);

        assertEquals(RagSearchTool.KB_UNAVAILABLE, out);
        assertFalse(out.contains("未找到相关内容"));
        assertFalse(out.equals(RagSearchTool.NO_HIT));
    }

    /**
     * 向量化失败：只回固定友好句，不得把向量维度、供应商信息等交给模型复述。
     */
    @Test
    void reportsEmbeddingFailureAsFriendlySentence() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any()))
                .thenThrow(new EmbeddingException("向量维度 1024 与知识库维度 768 不一致"));

        String out = tool.searchKnowledgeBase("退货政策", 5);

        assertEquals(RagSearchTool.KB_UNAVAILABLE, out);
        assertFalse(out.contains("1024"), "维度等内部参数不得出现在工具结果里");
        assertFalse(out.contains(RagSearchTool.NO_HIT), "故障不能被表述成没查到");
    }

    /**
     * 向量库连不上 / 超时：验收要求用户面只有友好句。
     * <p>
     * 这里刻意让异常 message 带主机、端口、启动脚本与 gRPC 状态码——
     * 真实场景下这些细节正是从 Provider 一路带上来的，必须在这一层被切断。
     */
    @Test
    void vectorStoreOutageNeverLeaksInfraDetails() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any()))
                .thenThrow(new RagStoreException("无法连接向量库（http://127.0.0.1:19530）："
                        + "StatusRuntimeException: DEADLINE_EXCEEDED。"
                        + "请确认 Milvus 已启动（本机可运行 D:\\Environment\\milvus\\start.py）"));

        String out = tool.searchKnowledgeBase("退货政策", 5);

        assertEquals(RagSearchTool.KB_UNAVAILABLE, out);
        assertFalse(out.contains("127.0.0.1"));
        assertFalse(out.contains("19530"));
        assertFalse(out.contains("start.py"));
        assertFalse(out.contains("DEADLINE_EXCEEDED"));
        assertFalse(out.contains("Exception"));
        assertFalse(out.contains("Milvus"));
        assertFalse(out.contains(RagSearchTool.NO_HIT));
    }

    /**
     * 未预期异常也不能把 message 交给模型：走通用「服务暂时不可用」。
     */
    @Test
    void unexpectedFailureFallsBackToGenericSentence() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));
        when(retrieval.retrieveMulti(any(), anyString(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("C:\\app\\data\\rag\\index 无法访问"));

        String out = tool.searchKnowledgeBase("退货政策", 5);

        assertEquals(UserFacingText.SERVICE_UNAVAILABLE, out);
        assertFalse(out.contains("C:\\"));
        assertFalse(out.contains("index"));
    }

    /**
     * query 为空：直接给提示，不查。
     */
    @Test
    void rejectsBlankQuery() {
        RagToolContext.bind(7L);

        assertEquals("检索问题不能为空。", tool.searchKnowledgeBase("  ", 5));
        verify(retrieval, never()).retrieveMulti(any(), anyString(), anyInt(), any(), any());
    }

    /**
     * 条数不再听模型：入参传什么都向检索服务传 0（由知识库配置决定最终条数）。
     * 这样知识库「检索参数」与 Agent 绑定覆盖项才真正生效。
     */
    @Test
    void ignoresModelSuppliedTopK() {
        RagToolContext.bind(7L);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb()));

        tool.searchKnowledgeBase("查询", 999);
        tool.searchKnowledgeBase("查询", -5);
        tool.searchKnowledgeBase("查询", null);

        // 三次都必须传 0（不覆盖配置），不能被夹到 1/10 或默认 5
        verify(retrieval, org.mockito.Mockito.times(3))
                .retrieveMulti(any(), anyString(), org.mockito.ArgumentMatchers.eq(0), any(), any());
    }

    /**
     * 上下文清理：clear 后必须拒绝，证明不会跨轮串 Agent。
     */
    @Test
    void clearsContextPreventsLeak() {
        RagToolContext.bind(7L);
        RagToolContext.clear();

        assertEquals(RagSearchTool.NO_BINDING, tool.searchKnowledgeBase("退货政策", 5));
    }

    private RagKnowledgeBase kb() {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(1L);
        kb.setName("售后库");
        kb.setStatus("READY");
        kb.setVisibility("PUBLIC");
        kb.setIsolationMode("PHYSICAL");
        kb.setCollectionName("agenthub_kb_1");
        kb.setStoreCapability("DENSE_ONLY");
        kb.setEmbeddingModelId(8L);
        kb.setVectorDimension(8);
        return kb;
    }

    private SearchResult hit() {
        SearchResult hit = new SearchResult();
        hit.setChunkId(1000L);
        hit.setDocId(100L);
        hit.setKbId(1L);
        hit.setTitle("a.txt");
        hit.setSource("a.txt");
        hit.setContent("七天无理由退货需商品完好");
        hit.setVectorScore(0.87d);
        hit.setHitBy("vector");
        return hit;
    }
}
