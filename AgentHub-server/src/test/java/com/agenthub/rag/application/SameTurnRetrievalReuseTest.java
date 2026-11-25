package com.agenthub.rag.application;

import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.runtime.application.TrialObsHooks;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同一轮跨专家复用知识库检索。所属域：rag。
 * <p>
 * 不连库、不打真实模型。出站只数嵌入和向量召回：第二次同条件应为 0 次新增。
 */
class SameTurnRetrievalReuseTest {

    private EmbeddingClient embeddings;
    private RagProvider provider;
    private RagRetrievalAppService retrieval;
    private AgentKbBindAppService binds;
    private RagSearchTool tool;
    private ObsTraceScope turn;

    /**
     * 每个用例单独一轮 trace。向量库和切片都是 mock，命中正文按库 id 区分。
     */
    @BeforeEach
    void setUp() {
        embeddings = mock(EmbeddingClient.class);
        provider = mock(RagProvider.class);
        RagChunkRepository chunks = mock(RagChunkRepository.class);
        when(chunks.listByIds(any())).thenReturn(List.of());
        when(provider.supportsBm25(any())).thenReturn(false);
        when(embeddings.embedOne(any(), anyString(), anyInt())).thenReturn(new float[] {0.1f});
        when(provider.searchVector(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            RagKnowledgeBase kb = invocation.getArgument(0);
            SearchResult hit = new SearchResult();
            hit.setKbId(kb.getId());
            hit.setChunkId(kb.getId());
            hit.setTitle("片段");
            hit.setSource("doc");
            hit.setContent("库" + kb.getId() + "的正文");
            hit.setVectorScore(0.9d);
            hit.setHitBy("vector");
            return List.of(hit);
        });
        retrieval = new RagRetrievalAppService(mock(RagKnowledgeBaseRepository.class), chunks, provider,
                embeddings, mock(RerankClient.class));
        binds = mock(AgentKbBindAppService.class);
        when(binds.overridesFor(any())).thenReturn(Map.of());
        AgentRerankLookup rerankLookup = mock(AgentRerankLookup.class);
        when(rerankLookup.enabledRerankModelId(any())).thenReturn(Optional.empty());
        tool = new RagSearchTool(retrieval, binds, rerankLookup, new ObjectMapper());
        turn = new ObsTraceScope("turn-1", mock(TraceRecorder.class));
        ObsTraceHolder.bind(turn);
    }

    /**
     * 用例结束解开 trace 和工具身份，避免串到下一个用例。
     */
    @AfterEach
    void tearDown() {
        RagToolContext.clear();
        ObsTraceHolder.clear();
    }

    /**
     * 同一轮、同一库、同一查询：第二次不再嵌入、不再打向量库，正文仍是同一份。
     */
    @Test
    void sameTurnSameConditionsDoesNotSearchAgain() {
        RagKnowledgeBase kb = kb(11L);
        List<SearchResult> first = retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 5, Map.of(), null);
        List<SearchResult> second = retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 5, Map.of(), null);

        assertEquals("库11的正文", first.get(0).getContent());
        assertEquals(first.get(0).getContent(), second.get(0).getContent());
        verify(embeddings, times(1)).embedOne(any(), anyString(), anyInt());
        verify(provider, times(1)).searchVector(any(), anyString(), anyInt(), any());
    }

    /**
     * 查询文本或条数不同，仍然各自出站。不把两句不同的问题并成一次。
     */
    @Test
    void differentConditionsStillSearch() {
        RagKnowledgeBase kb = kb(11L);
        retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 5, Map.of(), null);
        retrieval.retrieveMulti(List.of(kb), "台账怎么核对", 5, Map.of(), null);
        retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 3, Map.of(), null);

        verify(embeddings, times(3)).embedOne(any(), anyString(), anyInt());
        verify(provider, times(3)).searchVector(any(), anyString(), anyInt(), any());
    }

    /**
     * 轮结束清掉本轮槽；下一条消息即使问句相同也要重新出站。
     * 换一个新的 trace 同样看不到上一轮。
     */
    @Test
    void turnEndDoesNotLeakIntoNextTurn() {
        RagKnowledgeBase kb = kb(11L);
        retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 5, Map.of(), null);

        new TrialObsHooks(mock(TraceRecorder.class)).clear();
        ObsTraceHolder.bind(turn);
        retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 5, Map.of(), null);

        ObsTraceScope next = new ObsTraceScope("turn-2", mock(TraceRecorder.class));
        ObsTraceHolder.bind(next);
        retrieval.retrieveMulti(List.of(kb), "年假怎么规定", 5, Map.of(), null);

        verify(embeddings, times(3)).embedOne(any(), anyString(), anyInt());
        verify(provider, times(3)).searchVector(any(), anyString(), anyInt(), any());
    }

    /**
     * 没有绑定该库的专家拿不到别人查过的正文，也不会因此少查自己有权的库。
     */
    @Test
    void unauthorizedAgentCannotReuse() {
        RagKnowledgeBase allowed = kb(11L);
        RagKnowledgeBase other = kb(22L);
        when(binds.listUsableForAgent(1L)).thenReturn(List.of(allowed));
        when(binds.listUsableForAgent(2L)).thenReturn(List.of());
        when(binds.listUsableForAgent(3L)).thenReturn(List.of(other));

        RagToolContext.bind(1L);
        String first = tool.searchKnowledgeBase("年假怎么规定", 5);
        RagToolContext.bind(2L);
        String denied = tool.searchKnowledgeBase("年假怎么规定", 5);
        RagToolContext.bind(3L);
        String ownKb = tool.searchKnowledgeBase("年假怎么规定", 5);

        assertTrue(first.contains("库11的正文"));
        assertEquals(RagSearchTool.NO_BINDING, denied);
        assertFalse(denied.contains("库11的正文"));
        assertTrue(ownKb.contains("库22的正文"));
        assertFalse(ownKb.contains("库11的正文"));
        verify(embeddings, times(2)).embedOne(any(), anyString(), anyInt());
        verify(provider, times(2)).searchVector(any(), anyString(), anyInt(), any());
    }

    /**
     * @param id 知识库主键
     * @return 可供检索的库；嵌入维度给一个固定值，避免拆箱失败
     */
    private static RagKnowledgeBase kb(Long id) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(id);
        kb.setTenantId(0L);
        kb.setEmbeddingModelId(7L);
        kb.setVectorDimension(8);
        return kb;
    }
}
