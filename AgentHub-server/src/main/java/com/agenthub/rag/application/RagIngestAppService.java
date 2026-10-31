package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagDocumentStatus;
import com.agenthub.rag.domain.model.RagIngestStatus;
import com.agenthub.rag.domain.model.RagIngestTask;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.ChunkPipeline;
import com.agenthub.rag.domain.service.RagAccessPolicy;
import com.agenthub.rag.domain.service.RagUploadTypes;
import com.agenthub.rag.infra.parse.RagDocumentParser;
import com.agenthub.rag.infra.storage.RagFileStorage;
import com.agenthub.spi.rag.ChunkVector;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.EmbeddingException;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库用例。所属域：rag。② 期的核心。
 * <p>
 * 状态机：{@code PENDING → RUNNING → SUCCESS / FAILED}，失败可重试（复用同一任务行并 attempts +1）。
 * <p>
 * 关键设计：
 * <ol>
 *   <li><b>异步不占请求线程</b>：上传只做校验 + 落盘 + 建任务行，立即返回；解析/切片/向量化/写入在受限线程池执行。
 *       任务态落库而非内存，进程重启后管理员仍能看到失败原因并重试。</li>
 *   <li><b>先写 MySQL 拿 id，再写 Milvus</b>：Milvus 主键就是 rag_chunk.id，拿不到 id 无法双写对齐。</li>
 *   <li><b>失败清理</b>：任一步失败都清掉本次已写的切片与向量，避免留下「MySQL 有行、Milvus 没有」的半成品。</li>
 *   <li><b>维度强校验</b>：向量长度必须等于 KB.vector_dimension，由 EmbeddingClient 与 Provider 双重把关。</li>
 * </ol>
 * 事务边界：本类**不给入库主流程加 @Transactional**。入库包含解析、模型调用、向量库写入等长耗时外部调用，
 * 包进事务会长期占用连接且外部写不可回滚；一致性靠「失败补偿 + 任务状态」保证。
 */
@Service
public class RagIngestAppService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestAppService.class);

    /** 入库阶段名，与前端进度文案一致 */
    private static final String STAGE_PARSE = "解析";
    private static final String STAGE_CHUNK = "切片";
    private static final String STAGE_EMBED = "向量化";
    private static final String STAGE_WRITE = "写入";

    private final RagKnowledgeBaseRepository kbs;
    private final RagDocumentRepository documents;
    private final RagChunkRepository chunks;
    private final RagIngestTaskRepository tasks;
    private final RagProvider provider;
    private final EmbeddingClient embeddings;
    private final RagDocumentParser parser;
    private final RagFileStorage storage;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TaskExecutor ingestExecutor;

    /**
     * @param kbs            知识库
     * @param documents      文档
     * @param chunks         切片
     * @param tasks          入库任务
     * @param provider       向量库
     * @param embeddings     向量化（测试用 mock 替身注入）
     * @param parser         文档解析
     * @param storage        原文落盘
     * @param operatorContext 操作人
     * @param operLogWriter  操作日志
     * @param ingestExecutor 入库线程池
     */
    public RagIngestAppService(RagKnowledgeBaseRepository kbs, RagDocumentRepository documents,
                               RagChunkRepository chunks, RagIngestTaskRepository tasks,
                               RagProvider provider, EmbeddingClient embeddings,
                               RagDocumentParser parser, RagFileStorage storage,
                               OperatorContext operatorContext, OperLogWriter operLogWriter,
                               @Qualifier("ragIngestExecutor") TaskExecutor ingestExecutor) {
        this.kbs = kbs;
        this.documents = documents;
        this.chunks = chunks;
        this.tasks = tasks;
        this.provider = provider;
        this.embeddings = embeddings;
        this.parser = parser;
        this.storage = storage;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.ingestExecutor = ingestExecutor;
    }

    /**
     * 提交上传：校验 → 落盘 → 建文档与任务 → 异步执行。
     * <p>
     * 同一 KB 下同名文档视为重灌：复用原文档行、代次 +1，并先清掉旧切片与旧向量。
     *
     * @param kbId     知识库
     * @param fileName 原始文件名
     * @param bytes    文件内容
     * @return 新建（或复用）的文档，status=PENDING
     * @throws BizException 知识库不可用、格式不支持、文件过大、内容为空
     */
    public RagDocument submit(Long kbId, String fileName, byte[] bytes) {
        RagKnowledgeBase kb = requireUsableKb(kbId);
        if (!RagUploadTypes.allowed(fileName)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的文件格式，仅支持 " + RagUploadTypes.describe());
        }
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件内容为空");
        }
        if (bytes.length > RagUploadTypes.MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "文件超过 " + (RagUploadTypes.MAX_BYTES / 1024 / 1024) + "MB 上限");
        }
        Long operatorId = operatorContext.requireUserId();
        String title = safeTitle(fileName);
        String hash = ChunkPipeline.hash(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        String ext = RagUploadTypes.extensionOf(fileName);

        RagDocument doc = documents.findByKbAndTitle(kbId, title).orElse(null);
        boolean replace = doc != null;
        if (replace) {
            // 重灌：先清旧数据，避免新旧切片混在一个文档下
            cleanDocVectors(kb, doc.getId());
            chunks.deleteByDocId(doc.getId());
            documents.bumpVersion(doc.getId());
        } else {
            doc = new RagDocument();
            doc.setTenantId(kb.getTenantId());
            doc.setKbId(kbId);
            doc.setTitle(title);
            doc.setMime(RagUploadTypes.mimeOf(ext));
            doc.setByteSize((long) bytes.length);
            doc.setContentHash(hash);
            doc.setChunkCount(0);
            doc.setStatus(RagDocumentStatus.PENDING.getCode());
            doc.setVersion(1);
            doc.setId(documents.insert(doc, operatorId));
        }
        // 落盘用 docId 命名：与库行强绑定，重灌直接覆盖；路径必须回写库，否则重试只能看到 NULL
        String relativePath = storage.save(kbId, doc.getId(), fileName, bytes);
        doc.setSourceUri(relativePath);
        documents.updateSourceUri(doc.getId(), relativePath);
        documents.updateStatus(doc.getId(), RagDocumentStatus.PENDING.getCode(), null, null);

        RagIngestTask task = existingTaskOrNew(doc.getId(), kbId, operatorId);
        startAsync(task.getId(), doc.getId(), kbId, relativePath, replace);

        operLogWriter.write("rag", "upload", "rag_document", String.valueOf(doc.getId()),
                (replace ? "重灌文档 " : "上传文档 ") + title);
        return documents.findById(doc.getId()).orElse(doc);
    }

    /**
     * 重试入库任务。
     * <p>
     * 允许：{@code FAILED}；以及僵死的 {@code RUNNING}（进度仍 &lt;10，典型为后台线程在 markRunning
     * 后因无 Web 上下文读 Sa-Token 崩掉，UI 长期「解析中 / 排队中 0%」）。真正推进中的 RUNNING 拒绝，防双跑。
     *
     * @param docId 文档
     * @return 重置后的文档
     * @throws BizException 文档不存在、任务不在可重试状态、原文件缺失
     */
    public RagDocument retry(Long docId) {
        RagDocument doc = documents.findById(docId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在"));
        RagKnowledgeBase kb = requireUsableKb(doc.getKbId());
        RagIngestTask task = tasks.findByDocId(docId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "该文档没有入库任务，请重新上传"));
        RagDocumentStatus status = RagDocumentStatus.parse(doc.getStatus());
        if (status == RagDocumentStatus.RUNNING) {
            // 已进入向量化等阶段的 RUNNING 不能抢；仅收尸「排队中 0%」类僵死
            int progress = task.getProgress() == null ? 0 : task.getProgress();
            if (progress >= 10) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文档正在入库，请等待完成后再重试");
            }
        } else if (status != RagDocumentStatus.FAILED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有失败或卡住的任务可以重试");
        }
        // 历史缺陷：upload 曾只落盘不写 source_uri；按 kbId/docId/扩展名回填，避免僵死单只能重传
        String sourceUri = ensureSourceUri(doc);
        // 重试前清掉上次可能残留的半成品
        cleanDocVectors(kb, docId);
        chunks.deleteByDocId(docId);
        tasks.resetForRetry(task.getId());
        documents.updateStatus(docId, RagDocumentStatus.PENDING.getCode(), null, null);
        startAsync(task.getId(), docId, kb.getId(), sourceUri, true);
        operLogWriter.write("rag", "retry", "rag_document", String.valueOf(docId),
                "重试入库 " + doc.getTitle());
        return documents.findById(docId).orElse(doc);
    }

    /**
     * 按新的片长重切某文档并重建向量。
     * <p>
     * 与 {@link #retry(Long)} 的区别：重试只针对失败任务且沿用原参数；
     * 重切允许在 READY 状态下主动调整片长，因此必须先清掉旧切片与旧向量，
     * 否则同一文档会同时存在两代切片，检索召回会出现重复片段。
     *
     * @param docId       文档
     * @param chunkSize   目标片长（字符）
     * @param chunkOverlap 重叠字符，可空（沿用知识库参数）
     * @return 已受理的文档，status=PENDING
     * @throws BizException 文档不存在、知识库不可用、文档正在入库
     */
    public RagDocument rechunk(Long docId, int chunkSize, Integer chunkOverlap) {
        RagDocument doc = documents.findById(docId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在"));
        RagKnowledgeBase kb = requireUsableKb(doc.getKbId());
        // 入库中重切会让两次任务并发写同一文档的切片，直接拒绝
        RagDocumentStatus status = RagDocumentStatus.parse(doc.getStatus());
        if (status == RagDocumentStatus.PENDING || status == RagDocumentStatus.RUNNING) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文档正在入库，请等待完成后再重新切片");
        }
        if (doc.getSourceUri() == null || doc.getSourceUri().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原文件缺失，请重新上传该文档");
        }
        IngestParams params = IngestParams.overrideFrom(kb.getIngestConfig(), chunkSize, chunkOverlap);

        // 先清旧数据：向量库、切片都要归零，避免新一代切片与旧的混在一起。
        // 任务行由 existingTaskOrNew 复用并已重置为 PENDING；文档状态由 startAsync 统一置 PENDING
        cleanDocVectors(kb, docId);
        chunks.deleteByDocId(docId);
        RagIngestTask task = existingTaskOrNew(docId, kb.getId(), currentOperatorOrZero());
        startAsync(task.getId(), docId, kb.getId(), doc.getSourceUri(), true, params);

        operLogWriter.write("rag", "rechunk", "rag_document", String.valueOf(docId),
                "重新切片 " + doc.getTitle() + "，片长 " + params.chunkSize());
        return documents.findById(docId).orElse(doc);
    }

    /**
     * 删除文档：级联清 Milvus 向量 → 物理删切片 → 逻辑删文档 → 删任务 → 删原文件。
     * <p>
     * 顺序很重要：先删向量再删 MySQL。反过来的话一旦 Milvus 失败，MySQL 里已无行可定位，只能留下孤儿向量。
     *
     * @param docId 文档
     */
    public void remove(Long docId) {
        RagDocument doc = documents.findById(docId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在"));
        RagKnowledgeBase kb = kbs.findById(doc.getKbId()).orElse(null);
        if (kb != null) {
            cleanDocVectors(kb, docId);
        }
        chunks.deleteByDocId(docId);
        tasks.deleteByDocId(docId);
        documents.logicalDelete(docId);
        storage.delete(doc.getSourceUri());
        operLogWriter.write("rag", "remove-doc", "rag_document", String.valueOf(docId),
                "删除文档 " + doc.getTitle());
    }

    /**
     * 异步执行入口。
     */
    private void startAsync(Long taskId, Long docId, Long kbId, String sourceUri, boolean resetFirst) {
        startAsync(taskId, docId, kbId, sourceUri, resetFirst, null);
    }

    /**
     * 异步执行入口（可带切片参数覆盖）。
     *
     * @param overrideChunk 本次执行的切片参数覆盖项；null 表示沿用知识库 ingest_config
     */
    private void startAsync(Long taskId, Long docId, Long kbId, String sourceUri, boolean resetFirst,
                            IngestParams overrideChunk) {
        documents.updateStatus(docId, RagDocumentStatus.PENDING.getCode(), null, null);
        // 入队即打点：若只有这条没有「入库开始」，说明线程池未执行 Runnable
        log.info("[RAG] 入库入队 taskId={} docId={} kbId={}", taskId, docId, kbId);
        ingestExecutor.execute(() -> {
            try {
                run(taskId, docId, kbId, sourceUri, resetFirst, overrideChunk);
            } catch (Throwable t) {
                // run 内部已尽量 fail；此处兜住 Error 等，避免线程静默死掉后 UI 永久停在解析中
                log.error("[RAG] 入库线程未捕获异常 taskId={} docId={}", taskId, docId, t);
                try {
                    fail(taskId, docId, truncate("入库线程异常：" + t.getClass().getSimpleName(), 480));
                } catch (RuntimeException ignored) {
                    // fail 再失败只能打日志，不能再抛
                }
            }
        });
    }

    /**
     * 实际执行入库。逐阶段推进任务进度，失败时清理并落 FAILED。
     * <p>
     * 该方法跑在入库线程池里，不持有 Web 请求上下文，因此不读 {@code OperatorContext}。
     */
    void run(Long taskId, Long docId, Long kbId, String sourceUri, boolean resetFirst) {
        run(taskId, docId, kbId, sourceUri, resetFirst, null);
    }

    /**
     * 实际执行入库（可带切片参数覆盖）。
     * <p>
     * {@code markRunning} 必须在 try 内：此前放在 try 外时，任意 RuntimeException（含后台线程调
     * Sa-Token 无上下文）会让线程直接死掉，文档已是 RUNNING、任务停在「排队中 0%」，且打不出 /embeddings。
     * <p>
     * 本方法<strong>禁止</strong>读 {@link OperatorContext}：入库线程无 Web 请求，
     * {@code StpUtil.isLogin()} 会抛 {@code SaTokenContextException}。操作人取任务行 createdBy。
     *
     * @param overrideChunk 手动重切时传入的片长/重叠；null 用知识库入库参数
     */
    void run(Long taskId, Long docId, Long kbId, String sourceUri, boolean resetFirst,
             IngestParams overrideChunk) {
        RagKnowledgeBase kb = null;
        try {
            log.info("[RAG] 入库开始 taskId={} docId={} kbId={}", taskId, docId, kbId);
            tasks.markRunning(taskId);
            documents.updateStatus(docId, RagDocumentStatus.RUNNING.getCode(), null, null);
            kb = kbs.findById(kbId).orElse(null);
            if (kb == null) {
                fail(taskId, docId, "知识库已被删除");
                return;
            }
            // 标题与操作人只查库：后台线程无登录态，禁止走 OperatorContext / Sa-Token
            RagDocument doc = documents.findById(docId).orElse(null);
            String title = doc == null || doc.getTitle() == null ? "upload.bin" : doc.getTitle();
            Long operatorId = tasks.findById(taskId)
                    .map(RagIngestTask::getCreatedBy)
                    .orElse(0L);
            if (operatorId == null) {
                operatorId = 0L;
            }

            // 1) 解析（本地默认；MinerU/Vision 可选，失败回退，无 Key 不炸）
            progress(taskId, STAGE_PARSE, 10);
            byte[] raw = storage.read(sourceUri);
            if (raw.length == 0) {
                throw new IllegalStateException("原文件已丢失，请重新上传");
            }
            String text = parser.parse(title, raw);

            // 2) 切前清洗 → IR+原型 → 四策略 → 切后去噪（ingest_config 可盖默认 450/100/150）
            progress(taskId, STAGE_CHUNK, 30);
            IngestParams params = overrideChunk == null ? IngestParams.fromKb(kb.getIngestConfig()) : overrideChunk;
            List<com.agenthub.rag.domain.service.DocumentChunk> pieces = ChunkPipeline.chunkRich(
                    title, text, params.chunkSize(), params.overlap(), params.minChunkSize());
            if (pieces.isEmpty()) {
                throw new IllegalStateException("正文切片后为空，请检查文件内容");
            }

            // 3) 向量化 N→N（分批；条数必须与请求一致，禁止静默丢片）
            List<String> texts = new ArrayList<>(pieces.size());
            for (com.agenthub.rag.domain.service.DocumentChunk piece : pieces) {
                texts.add(piece.getContent());
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            int batch = Math.max(1, embeddings.maxBatchSize());
            for (int from = 0; from < texts.size(); from += batch) {
                int to = Math.min(from + batch, texts.size());
                progress(taskId, STAGE_EMBED, 30 + (int) Math.round(40.0 * to / texts.size()));
                float[][] part = embeddings.embed(kb.getEmbeddingModelId(),
                        texts.subList(from, to), kb.getVectorDimension());
                if (part == null || part.length != to - from) {
                    throw new IllegalStateException("向量化返回条数与请求不一致");
                }
                for (float[] v : part) {
                    vectors.add(v);
                }
            }

            // 4) 先写 MySQL 拿主键，再写 Milvus，保证两边可对齐
            progress(taskId, STAGE_WRITE, 75);
            List<RagChunk> rows = buildChunks(pieces, kb, docId, operatorId);
            List<RagChunk> inserted = chunks.insertBatch(rows, operatorId);
            List<ChunkVector> vectors2 = buildVectors(inserted, vectors, kb, docId, title);
            provider.upsertChunks(kb, vectors2);

            documents.updateStatus(docId, RagDocumentStatus.READY.getCode(), inserted.size(), null);
            tasks.markSuccess(taskId);
            log.info("[RAG] 入库成功 docId={} kbId={} chunks={}", docId, kbId, inserted.size());
        } catch (EmbeddingException | RagStoreException | IllegalArgumentException | IllegalStateException e) {
            // 业务可解释的失败：保留原文，清掉半成品，允许重试
            cleanupHalfDone(kb, docId);
            fail(taskId, docId, truncate(e.getMessage(), 480));
            log.warn("[RAG] 入库失败 docId={} reason={}", docId, e.getMessage());
        } catch (RuntimeException e) {
            cleanupHalfDone(kb, docId);
            fail(taskId, docId, truncate("入库异常：" + e.getClass().getSimpleName(), 480));
            log.error("[RAG] 入库异常 docId={}", docId, e);
        }
    }

    /**
     * 清理失败留下的半成品：向量与切片都可能写了一部分。
     * 这里吞掉清理异常——主流程已经失败，清理失败不应覆盖原始错误信息。
     */
    private void cleanupHalfDone(RagKnowledgeBase kb, Long docId) {
        if (kb != null) {
            try {
                provider.deleteByDocId(kb, docId);
            } catch (RuntimeException e) {
                log.warn("[RAG] 失败清理向量未完成 docId={} reason={}", docId, e.getMessage());
            }
        }
        try {
            chunks.deleteByDocId(docId);
        } catch (RuntimeException e) {
            log.warn("[RAG] 失败清理切片未完成 docId={} reason={}", docId, e.getMessage());
        }
    }

    /**
     * 文档级向量清理。KB 集合还没初始化时直接跳过：此时本来就没有向量。
     */
    private void cleanDocVectors(RagKnowledgeBase kb, Long docId) {
        if (kb == null || kb.getCollectionName() == null || kb.getCollectionName().isBlank()) {
            return;
        }
        try {
            provider.deleteByDocId(kb, docId);
        } catch (RagStoreException e) {
            // 向量库不可用不应阻止删文档：记录仍在，运维可用任务表核对
            log.warn("[RAG] 清理文档向量失败 docId={} reason={}", docId, e.getMessage());
        }
    }

    /**
     * 把 IR 切片落到 rag_chunk 行；partIndex 从元数据回填，正文优先用 IR content。
     * <p>
     * 若有 sectionTitle 且正文未以该标题开头，则前置一行标题，便于检索命中后阅读。
     */
    private List<RagChunk> buildChunks(List<com.agenthub.rag.domain.service.DocumentChunk> pieces,
                                       RagKnowledgeBase kb, Long docId, Long operatorId) {
        List<RagChunk> rows = new ArrayList<>(pieces.size());
        long now = System.currentTimeMillis() / 1000;
        for (int i = 0; i < pieces.size(); i++) {
            com.agenthub.rag.domain.service.DocumentChunk piece = pieces.get(i);
            String content = storeContent(piece);
            RagChunk chunk = new RagChunk();
            chunk.setTenantId(kb.getTenantId());
            chunk.setKbId(kb.getId());
            chunk.setDocId(docId);
            chunk.setChunkIndex(i);
            chunk.setPartIndex(metaInt(piece, "partIndex", "part_index"));
            chunk.setContent(content);
            chunk.setTokenEst(ChunkPipeline.estimateTokens(content));
            chunk.setVersion(1);
            chunk.setContentHash(ChunkPipeline.hash(content));
            chunk.setDeleted(0);
            chunk.setEffectiveFrom(now);
            chunk.setExpireTime(0L);
            chunk.setCreateBy(operatorId);
            rows.add(chunk);
        }
        return rows;
    }

    /**
     * 入库正文：保留 IR content；有章节名时前置，避免元数据列缺失导致检索看不到标题。
     */
    private static String storeContent(com.agenthub.rag.domain.service.DocumentChunk piece) {
        String body = piece.getContent() == null ? "" : piece.getContent().trim();
        String title = piece.getSectionTitle() == null ? "" : piece.getSectionTitle().trim();
        if (title.isEmpty() || body.startsWith(title)) {
            return body;
        }
        return title + "\n\n" + body;
    }

    private static Integer metaInt(com.agenthub.rag.domain.service.DocumentChunk piece, String... keys) {
        for (String key : keys) {
            Object v = piece.getMetadata().get(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                    // 继续试下一个 key
                }
            }
        }
        return null;
    }

    private List<ChunkVector> buildVectors(List<RagChunk> inserted, List<float[]> vectors,
                                           RagKnowledgeBase kb, Long docId, String title) {
        List<ChunkVector> out = new ArrayList<>(inserted.size());
        for (int i = 0; i < inserted.size(); i++) {
            RagChunk chunk = inserted.get(i);
            ChunkVector row = new ChunkVector();
            row.setId(chunk.getId());
            row.setKbId(kb.getId());
            row.setDocId(docId);
            row.setChunkId(chunk.getId());
            row.setTenantId(kb.getTenantId());
            row.setDeptId(0L);
            row.setVersion((long) (chunk.getVersion() == null ? 1 : chunk.getVersion()));
            row.setContentHash(chunk.getContentHash());
            row.setDeleted(0);
            row.setEffectiveFrom(chunk.getEffectiveFrom());
            row.setExpireTime(0L);
            row.setCreateBy(chunk.getCreateBy());
            row.setTitle(title);
            row.setSource(title);
            row.setContent(chunk.getContent());
            row.setEmbedding(vectors.get(i));
            out.add(row);
        }
        return out;
    }

    private void progress(Long taskId, String stage, int value) {
        tasks.updateProgress(taskId, stage, value);
    }

    private void fail(Long taskId, Long docId, String reason) {
        tasks.markFailed(taskId, reason);
        documents.updateStatus(docId, RagDocumentStatus.FAILED.getCode(), null, reason);
    }

    private RagIngestTask existingTaskOrNew(Long docId, Long kbId, Long operatorId) {
        RagIngestTask existing = tasks.findByDocId(docId).orElse(null);
        if (existing != null) {
            tasks.resetForRetry(existing.getId());
            return existing;
        }
        RagIngestTask task = new RagIngestTask();
        task.setKbId(kbId);
        task.setDocId(docId);
        task.setStatus(RagIngestStatus.PENDING.getCode());
        // 入队尚未被线程领取时的展示态；markRunning 后会改成「解析」
        task.setStage("排队中");
        task.setProgress(0);
        task.setAttempts(0);
        task.setId(tasks.insert(task, operatorId));
        return task;
    }

    /**
     * 仅 HTTP 请求线程可用：取当前登录人；无登录态时记 0（系统）。
     * 入库 {@link #run} 禁止调用本方法。
     */
    private Long currentOperatorOrZero() {
        Long id = operatorContext.userIdOrNull();
        return id == null ? 0L : id;
    }

    /**
     * 确保文档有可读的原文件路径；库中为空时按落盘约定尝试回填。
     *
     * @param doc 文档
     * @return 可用相对路径
     * @throws BizException 磁盘上也找不到文件
     */
    private String ensureSourceUri(RagDocument doc) {
        if (doc.getSourceUri() != null && !doc.getSourceUri().isBlank()) {
            return doc.getSourceUri();
        }
        String ext = RagUploadTypes.extensionOf(doc.getTitle());
        String guessed = doc.getKbId() + "/" + doc.getId() + (ext.isEmpty() ? "" : "." + ext);
        if (storage.read(guessed).length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原文件路径缺失，请重新上传该文档");
        }
        documents.updateSourceUri(doc.getId(), guessed);
        doc.setSourceUri(guessed);
        return guessed;
    }

    private RagKnowledgeBase requireUsableKb(Long kbId) {
        RagKnowledgeBase kb = kbs.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在"));
        String reason = RagAccessPolicy.rejectReason(kb);
        if (reason != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, reason);
        }
        return kb;
    }

    private static String safeTitle(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            name = "未命名";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "未知错误";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * KB 的入库参数。解析失败回落默认，不让一段坏 JSON 卡住入库。
     *
     * @param chunkSize    目标片长
     * @param overlap      重叠
     * @param minChunkSize 过短片合并阈值
     */
    record IngestParams(int chunkSize, int overlap, int minChunkSize) {

        /** 默认片长：与 mall 调优结论对齐，BGE 类模型 512 token 上限内较稳 */
        private static final int DEFAULT_SIZE = 450;
        /** 默认重叠 */
        private static final int DEFAULT_OVERLAP = 100;
        /** 默认最小片长 */
        private static final int DEFAULT_MIN = 150;

        /**
         * 按知识库 ingest_config 解析参数。
         *
         * @param json KB 的 ingest_config
         * @return 参数；非法值回落默认
         */
        static IngestParams fromKb(String json) {
            if (json == null || json.isBlank()) {
                return new IngestParams(DEFAULT_SIZE, DEFAULT_OVERLAP, DEFAULT_MIN);
            }
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                int size = node.path("chunkSize").asInt(DEFAULT_SIZE);
                int overlap = node.path("chunkOverlap").asInt(DEFAULT_OVERLAP);
                int min = node.path("minChunkSize").asInt(DEFAULT_MIN);
                return new IngestParams(size, overlap, min).normalized();
            } catch (Exception e) {
                return new IngestParams(DEFAULT_SIZE, DEFAULT_OVERLAP, DEFAULT_MIN);
            }
        }

        /**
         * 手动重切参数：片长取页面输入，重叠缺省沿用知识库值，最小片长始终沿用知识库。
         *
         * @param kbConfig     知识库 ingest_config
         * @param chunkSize    页面输入的片长
         * @param chunkOverlap 页面输入的重叠，可空
         * @return 归一化后的参数
         */
        static IngestParams overrideFrom(String kbConfig, int chunkSize, Integer chunkOverlap) {
            IngestParams base = fromKb(kbConfig);
            int overlap = chunkOverlap == null ? base.overlap() : chunkOverlap;
            return new IngestParams(chunkSize, overlap, base.minChunkSize()).normalized();
        }

        /**
         * 收紧到合理区间：片长过小会让切片碎成字节，过大则被模型截断。
         *
         * @return 归一化后的参数
         */
        private IngestParams normalized() {
            int size = Math.min(Math.max(chunkSize, 100), 4000);
            int ov = Math.min(Math.max(overlap, 0), size / 2);
            int min = Math.min(Math.max(minChunkSize, 0), size);
            return new IngestParams(size, ov, min);
        }
    }
}
