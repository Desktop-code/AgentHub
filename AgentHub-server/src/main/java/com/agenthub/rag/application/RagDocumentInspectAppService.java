package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.rag.api.dto.RagChunkPreviewResp;
import com.agenthub.rag.api.dto.RagChunkResp;
import com.agenthub.rag.api.dto.RagChunkUpdateReq;
import com.agenthub.rag.api.dto.RagParseTextResp;
import com.agenthub.rag.api.dto.RagRechunkPreviewResp;
import com.agenthub.rag.api.dto.RagRechunkReq;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagDocumentParse;
import com.agenthub.rag.domain.model.RagDocumentStatus;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.ChunkPipeline;
import com.agenthub.rag.domain.service.ChunkPresentation;
import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.ir.ChunkStrategyKind;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档原文对照、重切预览与单条切片编辑。所属域：rag 的 application。
 * <p>
 * 与 {@link RagIngestAppService} 的分工：
 * <ul>
 *   <li>本类只做「读 / 预览 / 单条改」；重切覆盖仍走 {@code RagIngestAppService#rechunk}，避免两套覆盖逻辑；</li>
 *   <li>预览严格只读：只读解析产物或原文件、只跑纯算法切片，不写 rag_chunk、不动向量、不改变文档状态；</li>
 *   <li>单条编辑只改目标切片一行，并只用该片 id upsert 一条向量，禁止整篇重切。</li>
 * </ul>
 * 所有方法都在 HTTP 请求线程执行；后台入库线程不调用本类。
 */
@Service
public class RagDocumentInspectAppService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentInspectAppService.class);
    /** 预览卡片上限：片数仍是全量，避免超大文档一次回传几万条把浏览器打满 */
    private static final int PREVIEW_LIMIT = 300;

    private final RagKnowledgeBaseRepository kbs;
    private final RagDocumentRepository documents;
    private final RagChunkRepository chunks;
    private final RagDocumentParseRepository parses;
    private final RagDocumentParser parser;
    private final RagFileStorage storage;
    private final RagProvider provider;
    private final EmbeddingClient embeddings;
    private final OperLogWriter operLogWriter;

    /**
     * @param kbs          知识库
     * @param documents    文档
     * @param chunks       切片
     * @param parses       解析文本产物
     * @param parser       文档解析（老文档只读重解析）
     * @param storage      原文落盘
     * @param provider     向量库（单条编辑只 upsert 一片）
     * @param embeddings   向量化
     * @param operLogWriter 操作日志
     */
    public RagDocumentInspectAppService(RagKnowledgeBaseRepository kbs, RagDocumentRepository documents,
                                        RagChunkRepository chunks, RagDocumentParseRepository parses,
                                        RagDocumentParser parser, RagFileStorage storage,
                                        RagProvider provider, EmbeddingClient embeddings,
                                        OperLogWriter operLogWriter) {
        this.kbs = kbs;
        this.documents = documents;
        this.chunks = chunks;
        this.parses = parses;
        this.parser = parser;
        this.storage = storage;
        this.provider = provider;
        this.embeddings = embeddings;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 文档解析文本：优先本轮入库产物；老文档从原文件只读再解析。
     * <p>
     * 该方法不写任何表，也不改切片与向量；解析失败把原因放在 message 里返回，不抛 500。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 解析文本或不可用原因
     */
    public RagParseTextResp parseText(Long kbId, Long docId) {
        RagDocument doc = requireDoc(kbId, docId);
        RagParseTextResp resp = new RagParseTextResp();
        RagDocumentParse stored = parses.findByDocId(docId).orElse(null);
        if (stored != null && stored.getContent() != null && !stored.getContent().isBlank()) {
            resp.setText(stored.getContent());
            resp.setCharCount(stored.getCharCount() == null ? stored.getContent().length() : stored.getCharCount());
            resp.setHeaderCount(stored.getHeaderCount() == null ? 0 : stored.getHeaderCount());
            resp.setFromSource(false);
            return resp;
        }
        // 老文档：允许从原文件只读再解析用于对照，绝不回写切片/向量
        String text = readSourceText(doc);
        if (text == null) {
            resp.setText("");
            resp.setCharCount(0);
            resp.setHeaderCount(0);
            resp.setFromSource(false);
            resp.setMessage("该文档没有解析产物且原文件缺失；重新切片后可看到完整对照");
            return resp;
        }
        resp.setText(text);
        resp.setCharCount(text.length());
        // 实时解析不额外跑结构分析，不编造标题数
        resp.setHeaderCount(0);
        resp.setFromSource(true);
        return resp;
    }

    /**
     * 重新切片预览：只读计算出「预计片数 + 新切片卡片」。
     * <p>
     * 不写 rag_chunk、不删向量、不改变文档状态；确认覆盖仍调用原 rechunk 接口。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @param req   片长与重叠（范围与确认覆盖完全一致）
     * @return 预览结果
     * @throws BizException 文档/知识库不存在、原文件与解析产物都缺失、正文切片为空
     */
    public RagRechunkPreviewResp preview(Long kbId, Long docId, RagRechunkReq req) {
        RagDocument doc = requireDoc(kbId, docId);
        RagKnowledgeBase kb = requireKb(kbId);
        // 确认覆盖仍要按原文件重灌；原文件缺失时预览出来也落不了库，提前拦截避免误导
        if (doc.getSourceUri() == null || doc.getSourceUri().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原文件缺失，请重新上传该文档");
        }
        String text = requireParseText(doc);
        // 与确认覆盖共用 IngestParams：预览片数与落库片数必须同源，不能在两处各写一套收紧规则
        RagIngestAppService.IngestParams params = RagIngestAppService.IngestParams.overrideFrom(
                kb.getIngestConfig(), req.getChunkSize(), req.getChunkOverlap());
        StructuredDocument ir = ChunkPipeline.structure(doc.getTitle(), text,
                params.chunkSize(), params.overlap(), params.minChunkSize());
        List<DocumentChunk> pieces = ChunkPipeline.chunkPrepared(ir,
                params.chunkSize(), params.overlap(), params.minChunkSize());
        if (pieces.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "按当前片长切片后为空，请调整参数");
        }
        ChunkPresentation.assignOffsets(pieces, text);

        RagRechunkPreviewResp resp = new RagRechunkPreviewResp();
        resp.setChunkSize(params.chunkSize());
        resp.setChunkOverlap(params.overlap());
        resp.setChunkCount(pieces.size());
        int limit = Math.min(pieces.size(), PREVIEW_LIMIT);
        List<RagChunkPreviewResp> cards = new ArrayList<>(limit);
        int tokenTotal = 0;
        for (int i = 0; i < pieces.size(); i++) {
            DocumentChunk piece = pieces.get(i);
            String content = ChunkPresentation.storedContent(piece);
            int token = ChunkPipeline.estimateTokens(content);
            tokenTotal += token;
            if (i >= limit) {
                continue;
            }
            RagChunkPreviewResp card = new RagChunkPreviewResp();
            card.setChunkIndex(i);
            String strategy = ChunkPresentation.strategyCode(piece);
            card.setStrategy(strategy);
            card.setStrategyLabel(ChunkStrategyKind.labelOf(strategy));
            card.setSectionPath(ChunkPresentation.sectionPath(piece));
            card.setPartIndex(metaInt(piece, "partIndex", "part_index"));
            card.setTokenEst(token);
            card.setContent(content);
            card.setCharStart(metaInt(piece, "char_start"));
            card.setCharEnd(metaInt(piece, "char_end"));
            cards.add(card);
        }
        resp.setTokenTotal(tokenTotal);
        resp.setChunks(cards);
        resp.setTruncated(pieces.size() > limit);
        return resp;
    }

    /**
     * 单条切片编辑：只更新这一片的正文 / 章节路径与向量。
     * <p>
     * 顺序：校验归属 → 先向量化（外部调用失败不改库）→ 更新该行 → 用该片 id upsert 一条向量。
     * 其它切片、序号、父块与偏移都不动，也不触发整篇重切。
     *
     * @param kbId    知识库
     * @param docId   文档
     * @param chunkId 切片
     * @param req     新正文与可选章节路径
     * @return 更新后的切片响应
     * @throws BizException 文档不存在、正在入库、切片不属于该文档、正文为空
     */
    public RagChunkResp updateChunk(Long kbId, Long docId, Long chunkId, RagChunkUpdateReq req) {
        RagDocument doc = requireDoc(kbId, docId);
        RagKnowledgeBase kb = requireKb(kbId);
        RagDocumentStatus status = RagDocumentStatus.parse(doc.getStatus());
        if (status == RagDocumentStatus.PENDING || status == RagDocumentStatus.RUNNING) {
            // 入库线程正在整篇写切片，此时改单片会被覆盖，直接拒绝
            throw new BizException(ErrorCode.BAD_REQUEST, "文档正在入库，请等待完成后再编辑切片");
        }
        RagChunk chunk = chunks.findById(chunkId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "切片不存在"));
        if (!docId.equals(chunk.getDocId()) || !kbId.equals(chunk.getKbId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "切片不存在");
        }
        String content = req.getContent() == null ? "" : req.getContent().trim();
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "切片正文不能为空");
        }
        // 先算向量：模型失败时数据库仍是旧正文，不会出现「库里新、向量旧」的静默半成品
        float[][] embedded;
        try {
            embedded = embeddings.embed(kb.getEmbeddingModelId(), List.of(content), kb.getVectorDimension());
        } catch (EmbeddingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "切片向量化失败：" + e.getMessage());
        }
        if (embedded == null || embedded.length != 1 || embedded[0] == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "切片向量化返回条数异常");
        }
        int version = (chunk.getVersion() == null ? 1 : chunk.getVersion()) + 1;
        int tokenEst = ChunkPipeline.estimateTokens(content);
        String hash = ChunkPipeline.hash(content);
        String sectionPath = req.getSectionPath() == null ? null : req.getSectionPath().trim();
        chunks.updateContent(chunkId, content, tokenEst, hash, version, sectionPath);

        // 只 upsert 这一片：id 不变，其它切片的向量完全不受影响
        try {
            provider.upsertChunks(kb, List.of(toVector(chunk, doc, kb, content, hash, version, embedded[0])));
        } catch (RagStoreException e) {
            log.warn("[RAG] 单条切片向量更新失败 docId={} chunkId={} reason={}", docId, chunkId, e.getMessage());
            throw new BizException(ErrorCode.INTERNAL, "正文已保存，但向量更新失败，请重试保存");
        }
        operLogWriter.write("rag", "edit-chunk", "rag_chunk", String.valueOf(chunkId),
                "编辑切片 #" + ((chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex()) + 1));

        chunk.setContent(content);
        chunk.setTokenEst(tokenEst);
        chunk.setContentHash(hash);
        chunk.setVersion(version);
        if (sectionPath != null) {
            chunk.setSectionPath(sectionPath);
        }
        return toChunkResp(chunk);
    }

    /**
     * 读 IR 元数据里的整型值（Number 或字符串）。
     *
     * @param piece 切片
     * @param keys  候选键，按顺序命中第一个
     * @return 整型；都没有时为 null
     */
    private static Integer metaInt(DocumentChunk piece, String... keys) {
        for (String key : keys) {
            Object value = piece.getMetadata().get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                    // 继续试下一个键
                }
            }
        }
        return null;
    }

    /**
     * 组装单条切片的向量行。标量字段与整篇入库保持一致，避免 Milvus 里同库两套口径。
     */
    private ChunkVector toVector(RagChunk chunk, RagDocument doc, RagKnowledgeBase kb,
                                 String content, String hash, int version, float[] vector) {
        ChunkVector row = new ChunkVector();
        row.setId(chunk.getId());
        row.setKbId(kb.getId());
        row.setDocId(chunk.getDocId());
        row.setChunkId(chunk.getId());
        row.setTenantId(kb.getTenantId());
        row.setDeptId(0L);
        row.setVersion((long) version);
        row.setContentHash(hash);
        row.setDeleted(0);
        row.setEffectiveFrom(chunk.getEffectiveFrom() == null ? 0L : chunk.getEffectiveFrom());
        row.setExpireTime(0L);
        row.setCreateBy(chunk.getCreateBy() == null ? 0L : chunk.getCreateBy());
        row.setTitle(doc.getTitle());
        row.setSource(doc.getTitle());
        row.setContent(content);
        row.setEmbedding(vector);
        return row;
    }

    private RagChunkResp toChunkResp(RagChunk chunk) {
        RagChunkResp resp = new RagChunkResp();
        resp.setId(chunk.getId());
        resp.setChunkIndex(chunk.getChunkIndex());
        resp.setParentChunkId(chunk.getParentChunkId());
        resp.setPartIndex(chunk.getPartIndex());
        resp.setStrategy(chunk.getStrategy());
        resp.setStrategyLabel(ChunkStrategyKind.labelOf(chunk.getStrategy()));
        resp.setSectionPath(chunk.getSectionPath());
        resp.setCharStart(chunk.getCharStart());
        resp.setCharEnd(chunk.getCharEnd());
        resp.setTokenEst(chunk.getTokenEst());
        resp.setContent(chunk.getContent());
        return resp;
    }

    /**
     * 预览必须拿到解析文本：优先产物，其次原文件只读解析。
     *
     * @param doc 文档
     * @return 非空解析文本
     * @throws BizException 两者都缺失
     */
    private String requireParseText(RagDocument doc) {
        RagDocumentParse stored = parses.findByDocId(doc.getId()).orElse(null);
        if (stored != null && stored.getContent() != null && !stored.getContent().isBlank()) {
            return stored.getContent();
        }
        String text = readSourceText(doc);
        if (text == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该文档没有解析产物且原文件缺失，请重新上传后再预览");
        }
        return text;
    }

    /**
     * 从原文件只读解析；失败或缺失返回 null，由调用方决定提示文案。
     *
     * @param doc 文档
     * @return 统一换行的解析文本；不可用为 null
     */
    private String readSourceText(RagDocument doc) {
        if (doc.getSourceUri() == null || doc.getSourceUri().isBlank()) {
            return null;
        }
        byte[] raw = storage.read(doc.getSourceUri());
        if (raw.length == 0) {
            return null;
        }
        try {
            String text = ChunkPipeline.normalizeNewlines(parser.parse(doc.getTitle(), raw));
            return text.isBlank() ? null : text;
        } catch (RuntimeException e) {
            log.warn("[RAG] 只读再解析失败 docId={} reason={}", doc.getId(), e.getMessage());
            return null;
        }
    }

    private RagKnowledgeBase requireKb(Long kbId) {
        return kbs.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在"));
    }

    /**
     * 校验文档确实属于该知识库：路径里的 kbId 只是路由，不能据此信任归属。
     */
    private RagDocument requireDoc(Long kbId, Long docId) {
        requireKb(kbId);
        RagDocument doc = documents.findById(docId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在"));
        if (!kbId.equals(doc.getKbId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return doc;
    }
}
