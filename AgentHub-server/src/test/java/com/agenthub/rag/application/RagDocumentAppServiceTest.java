package com.agenthub.rag.application;

import com.agenthub.common.error.BizException;
import com.agenthub.rag.api.dto.RagChunkResp;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档用例只读切片列表。所属域：rag 的 application。
 * <p>
 * 覆盖点选预览主路径：归属校验通过后按 docId 拉片并映射展示字段；不打 Embedding / Milvus。
 */
@ExtendWith(MockitoExtension.class)
class RagDocumentAppServiceTest {

    @Mock
    private RagKnowledgeBaseRepository kbs;
    @Mock
    private RagDocumentRepository documents;
    @Mock
    private RagIngestTaskRepository tasks;
    @Mock
    private RagChunkRepository chunks;
    @Mock
    private RagDocumentParseRepository parses;
    @Mock
    private RagIngestAppService ingest;

    private RagDocumentAppService service;

    @BeforeEach
    void setUp() {
        service = new RagDocumentAppService(kbs, documents, tasks, chunks, parses, ingest);
    }

    /**
     * 就绪文档：listChunks 返回同序 index + content，供右栏预览。
     */
    @Test
    void listChunksReturnsIndexAndContentInOrder() {
        when(kbs.findById(1L)).thenReturn(Optional.of(kb(1L)));
        when(documents.findById(14L)).thenReturn(Optional.of(doc(14L, 1L)));
        RagChunk c0 = chunk(100L, 0, "片甲");
        RagChunk c1 = chunk(101L, 1, "片乙");
        when(chunks.listByDocId(14L)).thenReturn(List.of(c0, c1));

        List<RagChunkResp> rows = service.listChunks(1L, 14L);

        assertEquals(2, rows.size());
        assertEquals(0, rows.get(0).getChunkIndex());
        assertEquals("片甲", rows.get(0).getContent());
        assertEquals(1, rows.get(1).getChunkIndex());
        assertEquals("片乙", rows.get(1).getContent());
        verify(chunks).listByDocId(14L);
    }

    /**
     * 无切片文档：空列表，不抛错（前端显示空态文案）。
     */
    @Test
    void listChunksEmptyWhenNoChunks() {
        when(kbs.findById(1L)).thenReturn(Optional.of(kb(1L)));
        when(documents.findById(14L)).thenReturn(Optional.of(doc(14L, 1L)));
        when(chunks.listByDocId(14L)).thenReturn(List.of());

        assertTrue(service.listChunks(1L, 14L).isEmpty());
    }

    /**
     * 文档不属于该知识库：404，防止用错 kbId/docId 串读。
     */
    @Test
    void listChunksRejectsDocOutsideKb() {
        when(kbs.findById(1L)).thenReturn(Optional.of(kb(1L)));
        when(documents.findById(14L)).thenReturn(Optional.of(doc(14L, 99L)));

        assertThrows(BizException.class, () -> service.listChunks(1L, 14L));
    }

    private static RagKnowledgeBase kb(Long id) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(id);
        return kb;
    }

    private static RagDocument doc(Long id, Long kbId) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setKbId(kbId);
        return doc;
    }

    private static RagChunk chunk(Long id, int index, String content) {
        RagChunk chunk = new RagChunk();
        chunk.setId(id);
        chunk.setChunkIndex(index);
        chunk.setTokenEst(12);
        chunk.setContent(content);
        return chunk;
    }
}
