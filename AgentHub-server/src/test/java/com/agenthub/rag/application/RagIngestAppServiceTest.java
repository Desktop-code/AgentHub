package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagIngestTask;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.infra.parse.RagDocumentParser;
import com.agenthub.rag.infra.storage.RagFileStorage;
import com.agenthub.spi.rag.ChunkVector;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.EmbeddingException;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import static org.mockito.Mockito.when;

/**
 * 入库状态机、级联删除与失败清理。所属域：rag 的 application。② 期验收主用例。
 * <p>
 * 全部用 mock 替身：EmbeddingClient 是桩、RagProvider 是桩，**不产生任何真实模型或 Milvus 调用**。
 * 覆盖点：
 * <ol>
 *   <li>成功路径：PENDING → RUNNING → SUCCESS，切片与向量双写；</li>
 *   <li>向量化失败：落 FAILED、清半成品、可重试；</li>
 *   <li>维度不符：由 EmbeddingClient 抛错，同样落 FAILED；</li>
 *   <li>DENSE_ONLY：写入不因缺 sparse 失败（Provider 桩模拟正常返回）；</li>
 *   <li>删文档：先删向量再删 MySQL；</li>
 *   <li>非 READY 知识库：拒绝上传，不建任务。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagIngestAppServiceTest {

    @Mock
    private RagKnowledgeBaseRepository kbs;
    @Mock
    private RagDocumentRepository documents;
    @Mock
    private RagChunkRepository chunks;
    @Mock
    private RagIngestTaskRepository tasks;
    @Mock
    private RagDocumentParseRepository parses;
    @Mock
    private RagProvider provider;
    @Mock
    private EmbeddingClient embeddings;
    @Mock
    private RagDocumentParser parser;
    @Mock
    private RagFileStorage storage;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private TaskExecutor executor;

    private RagIngestAppService service;

    @BeforeEach
    void setUp() {
        service = new RagIngestAppService(kbs, documents, chunks, tasks, provider, embeddings,
                parser, storage, parses, operatorContext, operLogWriter, executor);
        lenient().when(operatorContext.requireUserId()).thenReturn(9L);
        lenient().when(operatorContext.userIdOrNull()).thenReturn(9L);
        lenient().when(embeddings.maxBatchSize()).thenReturn(16);
    }

    /**
     * 成功路径：任务终态 SUCCESS，文档 READY，切片数正确，向量按主键写入。
     */
    @Test
    void ingestSucceedsAndWritesVectors() {
        stubKb("DENSE_ONLY");
        stubDocumentAndTask();
        when(storage.read("1/100.txt")).thenReturn("正文".getBytes());
        // 正文须超过空壳阈值（约 60 字），否则 ShellChunkProcessor 会整段丢弃
        when(parser.parse(anyString(), any())).thenReturn(
                "第一段说明退货政策与签收时效，买家需在七日内提交申请并保留完整包装。"
                        + "第二段说明运费承担规则与偏远地区附加费用。"
                        + "第三段说明质检时效与大件商品可延长的处理方式。");
        // 切片数量取决于清洗与合并，测试里按实际请求条数返回等长结果
        when(embeddings.embed(eq(8L), any(), eq(8))).thenAnswer(inv -> {
            List<?> texts = inv.getArgument(1);
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                out[i] = vec(8);
            }
            return out;
        });
        stubChunkInsert();

        // 即使 OperatorContext 在无 Web 上下文时会炸，run 也不得调用它（否则会卡在排队中 0%）
        when(operatorContext.userIdOrNull()).thenThrow(new RuntimeException("未能获取有效的上下文处理器"));

        service.run(50L, 100L, 1L, "1/100.txt", false);

        verify(tasks).markRunning(50L);
        verify(tasks).markSuccess(50L);
        verify(documents).updateStatus(eq(100L), eq("READY"), anyInt(), eq(null));
        verify(operatorContext, never()).userIdOrNull();
        verify(operatorContext, never()).requireUserId();
        // 向量写入必须发生，且行数与切片一致
        ArgumentCaptor<List<ChunkVector>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).upsertChunks(any(RagKnowledgeBase.class), captor.capture());
        assertTrue(captor.getValue().size() >= 1);
        assertEquals(1L, captor.getValue().get(0).getKbId());
    }

    /**
     * 向量化失败：任务 FAILED、文档 FAILED 且带原因，同时清掉已写的切片与向量。
     */
    @Test
    void ingestMarksFailedWhenEmbeddingThrows() {
        stubKb("DENSE_ONLY");
        stubDocumentAndTask();
        when(storage.read(anyString())).thenReturn("x".getBytes());
        when(parser.parse(anyString(), any())).thenReturn(
                "一些正文内容，足够长以产生切片，涵盖退货运费质检与平台纠纷介入等说明文字避免空壳过滤。");
        when(embeddings.embed(anyLong(), any(), anyInt()))
                .thenThrow(new EmbeddingException("向量化失败：HTTP 401 鉴权失败"));

        service.run(50L, 100L, 1L, "1/100.txt", false);

        verify(tasks).markFailed(eq(50L), anyString());
        verify(documents).updateStatus(eq(100L), eq("FAILED"), eq(null), anyString());
        // 失败必须清半成品，否则下次重试会叠加重复切片
        verify(provider).deleteByDocId(any(RagKnowledgeBase.class), eq(100L));
        verify(chunks).deleteByDocId(100L);
        verify(provider, never()).upsertChunks(any(), any());
    }

    /**
     * 向量库写入失败：同样 FAILED 并清理，不允许出现「MySQL 有行、Milvus 没有」。
     */
    @Test
    void ingestMarksFailedWhenUpsertThrows() {
        stubKb("DENSE_ONLY");
        stubDocumentAndTask();
        when(storage.read(anyString())).thenReturn("x".getBytes());
        when(parser.parse(anyString(), any())).thenReturn(
                "一些正文内容，足够长以产生切片，涵盖退货运费质检与平台纠纷介入等说明文字避免空壳过滤。");
        when(embeddings.embed(anyLong(), any(), anyInt())).thenAnswer(inv -> {
            List<?> texts = inv.getArgument(1);
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                out[i] = vec(8);
            }
            return out;
        });
        stubChunkInsert();
        doThrow(new RagStoreException("写入向量失败：向量库未就绪"))
                .when(provider).upsertChunks(any(), any());

        service.run(50L, 100L, 1L, "1/100.txt", false);

        verify(tasks).markFailed(eq(50L), anyString());
        verify(chunks).deleteByDocId(100L);
    }

    /**
     * 解析失败（如扫描件 PDF）：FAILED 且原因可读，不进入向量化阶段。
     */
    @Test
    void ingestMarksFailedWhenParseThrows() {
        stubKb("DENSE_ONLY");
        stubDocumentAndTask();
        when(storage.read(anyString())).thenReturn("x".getBytes());
        when(parser.parse(anyString(), any()))
                .thenThrow(new IllegalArgumentException("PDF 未提取到文字（可能是扫描件；本期不做 OCR）"));

        service.run(50L, 100L, 1L, "1/100.txt", false);

        verify(tasks).markFailed(eq(50L), anyString());
        verify(embeddings, never()).embed(anyLong(), any(), anyInt());
    }

    /**
     * 切片为空：明确失败，而不是写入 0 条向量后报「成功」。
     */
    @Test
    void ingestMarksFailedWhenNoChunks() {
        stubKb("DENSE_ONLY");
        stubDocumentAndTask();
        when(storage.read(anyString())).thenReturn("x".getBytes());
        when(parser.parse(anyString(), any())).thenReturn("   \n\n  ");

        service.run(50L, 100L, 1L, "1/100.txt", false);

        verify(tasks).markFailed(eq(50L), anyString());
        verify(provider, never()).upsertChunks(any(), any());
    }

    /**
     * 删文档：先删向量（需要 KB 集合名），再删切片与文档，最后删原文件。
     */
    @Test
    void removeCascadesVectorsAndRows() {
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setKbId(1L);
        doc.setTitle("a.txt");
        doc.setSourceUri("1/100.txt");
        when(documents.findById(100L)).thenReturn(Optional.of(doc));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));

        service.remove(100L);

        verify(provider).deleteByDocId(any(RagKnowledgeBase.class), eq(100L));
        verify(chunks).deleteByDocId(100L);
        verify(tasks).deleteByDocId(100L);
        verify(documents).logicalDelete(100L);
        verify(storage).delete("1/100.txt");
    }

    /**
     * 知识库未 READY：上传被拒，不建文档也不建任务。
     */
    @Test
    void submitRejectsUnusableKb() {
        RagKnowledgeBase failed = kb("DENSE_ONLY");
        failed.setStatus("INIT_FAILED");
        when(kbs.findById(1L)).thenReturn(Optional.of(failed));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenthub.common.error.BizException.class,
                () -> service.submit(1L, "a.txt", "hello".getBytes()));
        verify(documents, never()).insert(any(), anyLong());
        verify(tasks, never()).insert(any(), anyLong());
    }

    /**
     * 格式白名单：.xls 等不在允许范围（docx/图片已纳入解析）。
     */
    @Test
    void submitRejectsUnsupportedFormat() {
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenthub.common.error.BizException.class,
                () -> service.submit(1L, "a.xls", "hello".getBytes()));
        verify(documents, never()).insert(any(), anyLong());
    }

    /**
     * 上传必须把任务丢进入库线程池：只插 PENDING 不 execute，就会出现「排队中无人消费」。
     * 桩 executor 不跑 Runnable，只断言入队。
     */
    @Test
    void submitEnqueuesAsyncRun() {
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));
        when(documents.findByKbAndTitle(1L, "a.txt")).thenReturn(Optional.empty());
        when(documents.insert(any(), eq(9L))).thenReturn(100L);
        when(storage.save(eq(1L), eq(100L), eq("a.txt"), any())).thenReturn("1/100.txt");
        when(tasks.findByDocId(100L)).thenReturn(Optional.empty());
        when(tasks.insert(any(), eq(9L))).thenAnswer(inv -> {
            RagIngestTask task = inv.getArgument(0);
            task.setId(50L);
            return 50L;
        });
        when(documents.findById(100L)).thenAnswer(inv -> {
            RagDocument doc = new RagDocument();
            doc.setId(100L);
            doc.setKbId(1L);
            doc.setTitle("a.txt");
            doc.setStatus("PENDING");
            doc.setSourceUri("1/100.txt");
            return Optional.of(doc);
        });

        RagDocument submitted = service.submit(1L, "a.txt", "hello".getBytes());

        assertEquals(100L, submitted.getId());
        verify(tasks).insert(any(RagIngestTask.class), eq(9L));
        // 落盘路径必须回写，否则重试时 source_uri 为空
        verify(documents).updateSourceUri(100L, "1/100.txt");
        verify(executor).execute(any(Runnable.class));
        // 桩不执行 Runnable，故不应进入 markRunning / 向量化
        verify(tasks, never()).markRunning(anyLong());
        verify(embeddings, never()).embed(anyLong(), any(), anyInt());
    }

    /**
     * 僵死 RUNNING（排队中 0%）：允许重试收尸；真正推进中的 RUNNING 仍拒绝防双跑。
     */
    @Test
    void retryAcceptsZombieRunningAndEnqueues() {
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setKbId(1L);
        doc.setTitle("a.txt");
        doc.setStatus("RUNNING");
        doc.setSourceUri("1/100.txt");
        when(documents.findById(100L)).thenReturn(Optional.of(doc));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));
        RagIngestTask zombie = task();
        zombie.setStatus("RUNNING");
        zombie.setStage("排队中");
        zombie.setProgress(0);
        when(tasks.findByDocId(100L)).thenReturn(Optional.of(zombie));

        service.retry(100L);

        verify(tasks).resetForRetry(50L);
        verify(executor).execute(any(Runnable.class));
    }

    /**
     * 已进入解析/向量化的 RUNNING 不可重试，否则双跑写切片。
     */
    @Test
    void retryRejectsActiveRunning() {
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setKbId(1L);
        doc.setTitle("a.txt");
        doc.setStatus("RUNNING");
        doc.setSourceUri("1/100.txt");
        when(documents.findById(100L)).thenReturn(Optional.of(doc));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));
        RagIngestTask active = task();
        active.setStatus("RUNNING");
        active.setStage("向量化");
        active.setProgress(40);
        when(tasks.findByDocId(100L)).thenReturn(Optional.of(active));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenthub.common.error.BizException.class,
                () -> service.retry(100L));
        verify(executor, never()).execute(any(Runnable.class));
    }

    /**
     * 重切：必须先把旧向量与旧切片清掉再入队，否则同一文档会留下两代切片。
     */
    @Test
    void rechunkClearsOldChunksAndEnqueues() {
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setKbId(1L);
        doc.setTitle("a.txt");
        doc.setStatus("READY");
        doc.setSourceUri("1/100.txt");
        when(documents.findById(100L)).thenReturn(Optional.of(doc));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));
        when(tasks.findByDocId(100L)).thenReturn(Optional.of(task()));

        service.rechunk(100L, 300, 60);

        verify(provider).deleteByDocId(any(RagKnowledgeBase.class), eq(100L));
        verify(chunks).deleteByDocId(100L);
        // 复用任务行时 existingTaskOrNew 会重置为 PENDING，重切不再额外 reset 一次
        verify(tasks).resetForRetry(50L);
        // 文档回到 PENDING，等待入库线程按新片长重跑
        verify(documents).updateStatus(eq(100L), eq("PENDING"), eq(null), eq(null));
        // 任务被提交到线程池（桩里不会真的执行 run）
        verify(executor).execute(any(Runnable.class));
    }

    /**
     * 入库中重切：两次任务会并发写同一文档的切片，必须拒绝。
     */
    @Test
    void rechunkRejectsRunningDocument() {
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setKbId(1L);
        doc.setTitle("a.txt");
        doc.setStatus("RUNNING");
        when(documents.findById(100L)).thenReturn(Optional.of(doc));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("DENSE_ONLY")));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenthub.common.error.BizException.class,
                () -> service.rechunk(100L, 300, 60));
        verify(chunks, never()).deleteByDocId(anyLong());
        verify(executor, never()).execute(any(Runnable.class));
    }

    /**
     * 重切参数归一化：片长超上限被收紧到 4000，重叠不超过片长一半。
     */
    @Test
    void rechunkParamsAreClamped() {
        RagIngestAppService.IngestParams params =
                RagIngestAppService.IngestParams.overrideFrom(
                        "{\"chunkSize\":450,\"chunkOverlap\":100,\"minChunkSize\":150}", 9000, 5000);
        assertEquals(4000, params.chunkSize());
        assertEquals(2000, params.overlap());
        assertEquals(150, params.minChunkSize());
    }

    /**
     * 重切参数缺省：不传重叠时沿用知识库 ingest_config 的重叠值。
     */
    @Test
    void rechunkParamsFallBackToKbOverlap() {
        RagIngestAppService.IngestParams params =
                RagIngestAppService.IngestParams.overrideFrom(
                        "{\"chunkSize\":450,\"chunkOverlap\":100,\"minChunkSize\":150}", 200, null);
        assertEquals(200, params.chunkSize());
        assertEquals(100, params.overlap());
        assertEquals(150, params.minChunkSize());
    }

    private void stubKb(String capability) {
        when(kbs.findById(1L)).thenReturn(Optional.of(kb(capability)));
    }

    private void stubDocumentAndTask() {
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setKbId(1L);
        doc.setTitle("a.txt");
        doc.setStatus("RUNNING");
        when(documents.findById(100L)).thenReturn(Optional.of(doc));
        when(tasks.findByDocId(100L)).thenReturn(Optional.of(task()));
        // run 从任务行取 createdBy，禁止再读 OperatorContext
        when(tasks.findById(50L)).thenReturn(Optional.of(task()));
    }

    /**
     * 切片插入桩：模拟 MyBatis 回填自增主键，向量写入必须能拿到 id。
     */
    private void stubChunkInsert() {
        when(chunks.insertBatch(any(), anyLong())).thenAnswer(inv -> {
            List<RagChunk> rows = inv.getArgument(0);
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).setId(1000L + i);
                rows.get(i).setMilvusPk(1000L + i);
            }
            return rows;
        });
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
        kb.setVectorDimension(8);
        kb.setCreatedBy(9L);
        kb.setIngestConfig("{\"chunkSize\":100,\"chunkOverlap\":20,\"minChunkSize\":10}");
        return kb;
    }

    private RagIngestTask task() {
        RagIngestTask task = new RagIngestTask();
        task.setId(50L);
        task.setDocId(100L);
        task.setKbId(1L);
        task.setStatus("PENDING");
        task.setCreatedBy(9L);
        task.setProgress(0);
        return task;
    }

    private static float[] vec(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = 0.1f;
        }
        return v;
    }

    /**
     * 供断言提示：确保 service 构造不为空（防止未来构造参数顺序写错却静默通过）。
     */
    @Test
    void serviceConstructed() {
        assertNotNull(service);
    }
}
