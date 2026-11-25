package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.rag.api.dto.RagChunkResp;
import com.agenthub.rag.api.dto.RagChunkUpdateReq;
import com.agenthub.rag.api.dto.RagRechunkPreviewResp;
import com.agenthub.rag.api.dto.RagRechunkReq;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagDocumentParse;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.infra.parse.RagDocumentParser;
import com.agenthub.rag.infra.storage.RagFileStorage;
import com.agenthub.spi.rag.ChunkVector;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.RagProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档对照、重切预览与单条切片编辑。所属域：rag 的 application。
 * <p>
 * 全部用 mock 替身：EmbeddingClient / RagProvider 都是桩，不产生真实模型或向量库调用。
 * 覆盖口径：
 * <ol>
 *   <li>预览严格只读：不写切片、不写解析产物、不动向量、不改文档状态；</li>
 *   <li>单条编辑只更新目标片一行，并只 upsert 这一片的向量；</li>
 *   <li>空正文拒绝保存，且不触碰向量。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagDocumentInspectAppServiceTest {

    private static final String PARSE_TEXT = """
            # 退货政策
            买家需在签收后七日内提交退货申请，并保留完整包装与随货凭证，逾期平台不再受理。
            运费承担按责任方判定，质量问题由商家承担，非质量问题由买家承担且偏远地区另计附加费。

            ## 质检时效
            商家应在收到退货后四十八小时内完成质检并给出结论，大件商品可延长至七十二小时。
            平台介入时以质检记录与物流签收时间为准，双方均需在二十四小时内响应平台问询。
            """;

    @Mock
    private RagKnowledgeBaseRepository kbs;
    @Mock
    private RagDocumentRepository documents;
    @Mock
    private RagChunkRepository chunks;
    @Mock
    private RagDocumentParseRepository parses;
    @Mock
    private RagDocumentParser parser;
    @Mock
    private RagFileStorage storage;
    @Mock
    private RagProvider provider;
    @Mock
    private EmbeddingClient embeddings;
    @Mock
    private OperLogWriter operLogWriter;

    private RagDocumentInspectAppService service;

    @BeforeEach
    void setUp() {
        service = new RagDocumentInspectAppService(kbs, documents, chunks, parses,
                parser, storage, provider, embeddings, operLogWriter);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb()));
        when(documents.findById(14L)).thenReturn(Optional.of(doc()));
    }

    /**
     * 预览只读：片数来自解析产物，全程不得写库或改向量。
     */
    @Test
    void previewDoesNotWriteChunksOrVectors() {
        when(parses.findByDocId(14L)).thenReturn(Optional.of(parse()));

        RagRechunkPreviewResp resp = service.preview(1L, 14L, rechunkReq(300, 50));

        assertTrue(resp.getChunkCount() >= 1);
        assertTrue(resp.getTokenTotal() > 0);
        assertEquals(300, resp.getChunkSize());
        assertEquals(50, resp.getChunkOverlap());
        assertTrue(resp.getChunks().size() >= 1);
        // 预览必须严格只读：切片表、向量库、文档状态、解析产物都不动
        verifyNoInteractions(chunks);
        verifyNoInteractions(provider);
        verifyNoInteractions(storage);
        verify(documents, never()).updateStatus(anyLong(), anyString(), any(), any());
        verify(parses, never()).save(any());
    }

    /**
     * 单条编辑：只更新目标片正文与向量，其它片不重切。
     */
    @Test
    void updateChunkOnlyRewritesTargetChunkAndItsVector() {
        RagChunk target = chunk(1000L, 2, "旧正文");
        when(chunks.findById(1000L)).thenReturn(Optional.of(target));
        when(embeddings.embed(eq(8L), any(), eq(8))).thenReturn(new float[][]{vec(8)});

        RagChunkUpdateReq req = new RagChunkUpdateReq();
        req.setContent("新的切片正文");
        req.setSectionPath("退货政策 / 质检时效");

        RagChunkResp resp = service.updateChunk(1L, 14L, 1000L, req);

        assertEquals("新的切片正文", resp.getContent());
        assertEquals("退货政策 / 质检时效", resp.getSectionPath());
        // 只更新一行：不能删旧切片、不能重新插入整篇
        verify(chunks).updateContent(eq(1000L), eq("新的切片正文"), anyInt(), anyString(), eq(2), eq("退货政策 / 质检时效"));
        verify(chunks, never()).deleteByDocId(anyLong());
        verify(chunks, never()).insertBatch(any(), anyLong());

        ArgumentCaptor<List<ChunkVector>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).upsertChunks(any(RagKnowledgeBase.class), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(1000L, captor.getValue().get(0).getId());
        assertEquals("新的切片正文", captor.getValue().get(0).getContent());
    }

    /**
     * 空正文：明确拒绝，且不改库、不向量化。
     */
    @Test
    void updateChunkRejectsBlankContent() {
        when(chunks.findById(1000L)).thenReturn(Optional.of(chunk(1000L, 2, "旧正文")));

        RagChunkUpdateReq req = new RagChunkUpdateReq();
        req.setContent("   ");

        assertThrows(BizException.class, () -> service.updateChunk(1L, 14L, 1000L, req));
        verify(chunks, never()).updateContent(anyLong(), anyString(), anyInt(), anyString(), anyInt(), any());
        verifyNoInteractions(embeddings);
        verifyNoInteractions(provider);
    }

    private static RagRechunkReq rechunkReq(int size, int overlap) {
        RagRechunkReq req = new RagRechunkReq();
        req.setChunkSize(size);
        req.setChunkOverlap(overlap);
        return req;
    }

    private static RagKnowledgeBase kb() {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(0L);
        kb.setName("测试库");
        kb.setStatus("READY");
        kb.setVisibility("PRIVATE");
        kb.setIsolationMode("PHYSICAL");
        kb.setCollectionName("agenthub_kb_1");
        kb.setStoreCapability("DENSE_ONLY");
        kb.setEmbeddingModelId(8L);
        kb.setVectorDimension(8);
        kb.setCreatedBy(9L);
        kb.setIngestConfig("{\"chunkSize\":450,\"chunkOverlap\":100,\"minChunkSize\":10}");
        return kb;
    }

    private static RagDocument doc() {
        RagDocument doc = new RagDocument();
        doc.setId(14L);
        doc.setKbId(1L);
        doc.setTitle("退货政策.md");
        doc.setStatus("READY");
        doc.setSourceUri("1/14.md");
        return doc;
    }

    private static RagDocumentParse parse() {
        RagDocumentParse parse = new RagDocumentParse();
        parse.setId(1L);
        parse.setKbId(1L);
        parse.setDocId(14L);
        parse.setContent(PARSE_TEXT);
        parse.setCharCount(PARSE_TEXT.length());
        parse.setHeaderCount(2);
        return parse;
    }

    private static RagChunk chunk(Long id, int index, String content) {
        RagChunk chunk = new RagChunk();
        chunk.setId(id);
        chunk.setKbId(1L);
        chunk.setDocId(14L);
        chunk.setChunkIndex(index);
        chunk.setTokenEst(12);
        chunk.setVersion(1);
        chunk.setContent(content);
        return chunk;
    }

    private static float[] vec(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = 0.1f;
        }
        return v;
    }
}
