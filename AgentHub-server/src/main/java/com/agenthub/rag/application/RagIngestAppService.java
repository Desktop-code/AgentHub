package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagDocumentParse;
import com.agenthub.rag.domain.model.RagDocumentStatus;
import com.agenthub.rag.domain.model.RagIngestStage;
import com.agenthub.rag.domain.model.RagIngestStatus;
import com.agenthub.rag.domain.model.RagIngestTask;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.ChunkPipeline;
import com.agenthub.rag.domain.service.ChunkPresentation;
import com.agenthub.rag.domain.service.RagAccessPolicy;
import com.agenthub.rag.domain.service.RagUploadTypes;
import com.agenthub.rag.domain.service.ir.StructuredDocument;
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

    /** 入库阶段名，与前端阶段条固定五步一致；禁止在对外文案里出现存储实现名 */
    private static final String STAGE_PARSE = "解析";
    private static final String STAGE_STRUCT = "结构识别";
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
    private final RagDocumentParseRepository parses;
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
     * @param parses         解析文本产物（原文对照 / 重切预览）
     * @param operatorContext 操作人
     * @param operLogWriter  操作日志
     * @param ingestExecutor 入库线程池
     */
    public RagIngestAppService(RagKnowledgeBaseRepository kbs, RagDocumentRepository documents,
                               RagChunkRepository chunks, RagIngestTaskRepository tasks,
                               RagProvider provider, EmbeddingClient embeddings,
                               RagDocumentParser parser, RagFileStorage storage,
                               RagDocumentParseRepository parses,
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
        this.parses = parses;
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
        // 解析全文也要清：否则删文档后仍能通过产物表读到正文
        parses.deleteByDocId(docId);
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
        // 阶段摘要与「当前正在跑的阶段」放在 try 外：失败分支要能把该步标成 FAILED
        List<RagIngestStage> stages = new ArrayList<>();
        String currentStage = null;
        long stageStartedAt = 0L;
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
            IngestParams params = overrideChunk == null ? IngestParams.fromKb(kb.getIngestConfig()) : overrideChunk;

            // 1) 解析（本地默认；MinerU/Vision 可选，失败回退，无 Key 不炸）
            currentStage = STAGE_PARSE;
            stageStartedAt = beginStage(taskId, stages, currentStage);
            progress(taskId, STAGE_PARSE, 10);
            byte[] raw = storage.read(sourceUri);
            if (raw.length == 0) {
                throw new IllegalStateException("原文件已丢失，请重新上传");
            }
            // 换行统一为 \n：解析产物、切片偏移与前端对照共用同一坐标系
            String text = ChunkPipeline.normalizeNewlines(parser.parse(title, raw));
            saveParseArtifact(kb, doc, docId, text);
            finishStage(taskId, stages, currentStage, stageStartedAt, "解析出 " + text.length() + " 字符");

            // 2) 结构识别：把现有 IR + 原型分析做成可见阶段，判定规则不改
            currentStage = STAGE_STRUCT;
            stageStartedAt = beginStage(taskId, stages, currentStage);
            progress(taskId, STAGE_STRUCT, 22);
            StructuredDocument ir = ChunkPipeline.structure(title, text,
                    params.chunkSize(), params.overlap(), params.minChunkSize());
            Integer headerMeta = metaInt(ir.getMetadata().get("headerCount"));
            int headerCount = headerMeta == null ? 0 : headerMeta;
            String docType = ir.getDocType() == null ? null : ir.getDocType().name();
            parses.updateStats(docId, headerCount, docType, null);
            finishStage(taskId, stages, currentStage, stageStartedAt,
                    "识别到 " + headerCount + " 个标题" + (docType == null ? "" : " · " + docTypeLabel(docType)));

            // 3) 切前清洗 → IR+原型 → 四策略 → 切后去噪（复用上一步 IR，不重复结构分析）
            currentStage = STAGE_CHUNK;
            stageStartedAt = beginStage(taskId, stages, currentStage);
            progress(taskId, STAGE_CHUNK, 35);
            List<com.agenthub.rag.domain.service.DocumentChunk> pieces = ChunkPipeline.chunkPrepared(
                    ir, params.chunkSize(), params.overlap(), params.minChunkSize());
            if (pieces.isEmpty()) {
                throw new IllegalStateException("正文切片后为空，请检查文件内容");
            }
            // 原文映射与展示元数据：只读 IR 产物，不改切分结果
            ChunkPresentation.assignOffsets(pieces, text);
            String strategy = dominantStrategy(pieces);
            parses.updateStats(docId, headerCount, docType, strategy);
            finishStage(taskId, stages, currentStage, stageStartedAt, "生成 " + pieces.size() + " 片");

            // 4) 向量化 N→N（分批；条数必须与请求一致，禁止静默丢片）
            currentStage = STAGE_EMBED;
            stageStartedAt = beginStage(taskId, stages, currentStage);
            List<String> texts = new ArrayList<>(pieces.size());
            for (com.agenthub.rag.domain.service.DocumentChunk piece : pieces) {
                texts.add(piece.getContent());
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            int batch = Math.max(1, embeddings.maxBatchSize());
            for (int from = 0; from < texts.size(); from += batch) {
                int to = Math.min(from + batch, texts.size());
                progress(taskId, STAGE_EMBED, 45 + (int) Math.round(35.0 * to / texts.size()));
                float[][] part = embeddings.embed(kb.getEmbeddingModelId(),
                        texts.subList(from, to), kb.getVectorDimension());
                if (part == null || part.length != to - from) {
                    throw new IllegalStateException("向量化返回条数与请求不一致");
                }
                for (float[] v : part) {
                    vectors.add(v);
                }
            }
            finishStage(taskId, stages, currentStage, stageStartedAt,
                    "向量化 " + texts.size() + " 条 · 维度 " + kb.getVectorDimension());

            // 5) 先写 MySQL 拿主键，再写 Milvus，保证两边可对齐
            currentStage = STAGE_WRITE;
            stageStartedAt = beginStage(taskId, stages, currentStage);
            progress(taskId, STAGE_WRITE, 82);
            List<RagChunk> rows = buildChunks(pieces, kb, docId, operatorId);
            List<RagChunk> inserted = chunks.insertBatch(rows, operatorId);
            List<ChunkVector> vectors2 = buildVectors(inserted, vectors, kb, docId, title);
            provider.upsertChunks(kb, vectors2);
            finishStage(taskId, stages, currentStage, stageStartedAt, "写入切片 " + inserted.size() + " 条");

            documents.updateStatus(docId, RagDocumentStatus.READY.getCode(), inserted.size(), null);
            tasks.markSuccess(taskId);
            log.info("[RAG] 入库成功 docId={} kbId={} chunks={}", docId, kbId, inserted.size());
        } catch (EmbeddingException | RagStoreException | IllegalArgumentException | IllegalStateException e) {
            // 业务可解释的失败：保留原文，标记当前阶段失败，清半成品后允许重试
            failStage(taskId, stages, currentStage, stageStartedAt, e.getMessage());
            cleanupHalfDone(kb, docId);
            fail(taskId, docId, truncate(e.getMessage(), 480));
            log.warn("[RAG] 入库失败 docId={} reason={}", docId, e.getMessage());
        } catch (RuntimeException e) {
            failStage(taskId, stages, currentStage, stageStartedAt, "入库异常：" + e.getClass().getSimpleName());
            cleanupHalfDone(kb, docId);
            fail(taskId, docId, truncate("入库异常：" + e.getClass().getSimpleName(), 480));
            log.error("[RAG] 入库异常 docId={}", docId, e);
        }
    }

    /**
     * 开启一个阶段：先落 RUNNING，前端阶段条立刻能看到当前步。
     *
     * @param taskId 任务
     * @param stages 本地阶段列表（单线程写）
     * @param stage  阶段名
     * @return 阶段开始时间（毫秒）
     */
    private long beginStage(Long taskId, List<RagIngestStage> stages, String stage) {
        RagIngestStage entry = new RagIngestStage();
        entry.setStage(stage);
        entry.setStatus("RUNNING");
        stages.add(entry);
        tasks.updateStages(taskId, stages);
        return System.currentTimeMillis();
    }

    /**
     * 结束当前阶段：写 SUCCESS、耗时与简要产物；摘要截断到 200 字，避免任务行膨胀。
     *
     * @param taskId    任务
     * @param stages    本地阶段列表
     * @param stage     阶段名
     * @param startedAt 开始时间
     * @param summary   产物摘要
     */
    private void finishStage(Long taskId, List<RagIngestStage> stages, String stage,
                             long startedAt, String summary) {
        RagIngestStage entry = lastRunning(stages);
        if (entry == null) {
            entry = new RagIngestStage();
            entry.setStage(stage);
            stages.add(entry);
        }
        entry.setStatus("SUCCESS");
        entry.setElapsedMs(Math.max(0L, System.currentTimeMillis() - startedAt));
        entry.setSummary(truncate(summary, 200));
        tasks.updateStages(taskId, stages);
    }

    /**
     * 标记当前阶段失败：保留已完成阶段的摘要，失败步写原因，后续步骤不伪造。
     *
     * @param taskId    任务
     * @param stages    本地阶段列表
     * @param stage     阶段名（可能为空：还没进入任何阶段）
     * @param startedAt 开始时间；0 表示未开始
     * @param reason    失败原因
     */
    private void failStage(Long taskId, List<RagIngestStage> stages, String stage,
                           long startedAt, String reason) {
        if (stage == null) {
            return;
        }
        RagIngestStage entry = lastRunning(stages);
        if (entry == null) {
            entry = new RagIngestStage();
            entry.setStage(stage);
            stages.add(entry);
        }
        entry.setStatus("FAILED");
        entry.setElapsedMs(startedAt <= 0 ? null : Math.max(0L, System.currentTimeMillis() - startedAt));
        entry.setSummary(truncate(reason == null ? "未知原因" : reason, 200));
        tasks.updateStages(taskId, stages);
    }

    private static RagIngestStage lastRunning(List<RagIngestStage> stages) {
        for (int i = stages.size() - 1; i >= 0; i--) {
            if ("RUNNING".equals(stages.get(i).getStatus())) {
                return stages.get(i);
            }
        }
        return null;
    }

    /**
     * 落解析产物：本轮实际使用的解析文本，供文档页对照与重切预览。
     * 解析文本是派生产物，写失败按入库失败处理，避免页面长期显示旧文本。
     *
     * @param kb    知识库
     * @param doc   文档（可为空，空时不写）
     * @param docId 文档 id
     * @param text  解析文本
     */
    private void saveParseArtifact(RagKnowledgeBase kb, RagDocument doc, Long docId, String text) {
        RagDocumentParse parse = new RagDocumentParse();
        parse.setTenantId(kb.getTenantId());
        parse.setKbId(kb.getId());
        parse.setDocId(docId);
        parse.setContent(text);
        parse.setCharCount(text.length());
        parse.setHeaderCount(0);
        parse.setVersion(doc == null || doc.getVersion() == null ? 1 : doc.getVersion());
        parses.save(parse);
    }

    /**
     * 本轮切片实际使用的策略：同一文档通常只路由到一种策略，取第一片的编码即可；
     * 拿不到了解就返回 null，绝不为展示猜测。
     */
    private static String dominantStrategy(List<com.agenthub.rag.domain.service.DocumentChunk> pieces) {
        for (com.agenthub.rag.domain.service.DocumentChunk piece : pieces) {
            String code = ChunkPresentation.strategyCode(piece);
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    /**
     * IR 原型的对外中文说明，用于结构识别阶段摘要。
     */
    private static String docTypeLabel(String docType) {
        return switch (docType) {
            case "HIERARCHICAL" -> "层级型";
            case "ENTRY_BASED" -> "条目型";
            case "TABULAR" -> "表格型";
            case "NARRATIVE" -> "叙述型";
            default -> docType;
        };
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
     * 把 IR 切片落到 rag_chunk 行。
     * <p>
     * 除正文与 partIndex 外，补齐本轮可展示的元数据：策略、章节路径、解析文本偏移。
     * 旧逻辑「有 sectionTitle 且正文未以其开头则前置标题」保持不变，仍由 ChunkPresentation 统一。
     */
    private List<RagChunk> buildChunks(List<com.agenthub.rag.domain.service.DocumentChunk> pieces,
                                       RagKnowledgeBase kb, Long docId, Long operatorId) {
        List<RagChunk> rows = new ArrayList<>(pieces.size());
        long now = System.currentTimeMillis() / 1000;
        for (int i = 0; i < pieces.size(); i++) {
            com.agenthub.rag.domain.service.DocumentChunk piece = pieces.get(i);
            String content = ChunkPresentation.storedContent(piece);
            RagChunk chunk = new RagChunk();
            chunk.setTenantId(kb.getTenantId());
            chunk.setKbId(kb.getId());
            chunk.setDocId(docId);
            chunk.setChunkIndex(i);
            chunk.setPartIndex(metaInt(piece, "partIndex", "part_index"));
            chunk.setStrategy(ChunkPresentation.strategyCode(piece));
            chunk.setSectionPath(ChunkPresentation.sectionPath(piece));
            chunk.setCharStart(metaInt(piece, "char_start", "charStart"));
            chunk.setCharEnd(metaInt(piece, "char_end", "charEnd"));
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
     * 读 IR 元数据里的整型值（兼容 JSON 反序列化出的 Number 与字符串）。
     *
     * @param value 元数据值
     * @return 整型；无法解析时为 null
     */
    private static Integer metaInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
