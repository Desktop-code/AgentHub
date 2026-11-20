package com.agenthub.rag.application;

import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.rag.api.dto.RagChunkResp;
import com.agenthub.rag.api.dto.RagDocumentResp;
import com.agenthub.rag.api.dto.RagRechunkReq;
import com.agenthub.rag.api.dto.RagStageResp;
import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.model.RagIngestStage;
import com.agenthub.rag.domain.model.RagIngestTask;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.ir.ChunkStrategyKind;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档列表与增删用例。所属域：rag。② 期。
 * <p>
 * 这里是 HTTP 层的入口：只做「知识库归属校验 + 视图组装」，
 * 真正的入库编排在 {@link RagIngestAppService}，检索在 {@link RagRetrievalAppService}。
 * 拆开的原因：入库要跑后台线程且不该被事务包住，而列表查询是纯读，混在一类里事务语义会互相干扰。
 */
@Service
public class RagDocumentAppService {

    private final RagKnowledgeBaseRepository kbs;
    private final RagDocumentRepository documents;
    private final RagIngestTaskRepository tasks;
    private final RagChunkRepository chunks;
    private final RagDocumentParseRepository parses;
    private final RagIngestAppService ingest;

    /**
     * @param kbs       知识库
     * @param documents 文档
     * @param tasks     入库任务（回填进度与阶段摘要）
     * @param chunks    切片（查看切片）
     * @param parses    解析文本产物（删文档时级联清理）
     * @param ingest    入库编排
     */
    public RagDocumentAppService(RagKnowledgeBaseRepository kbs, RagDocumentRepository documents,
                                 RagIngestTaskRepository tasks, RagChunkRepository chunks,
                                 RagDocumentParseRepository parses, RagIngestAppService ingest) {
        this.kbs = kbs;
        this.documents = documents;
        this.tasks = tasks;
        this.chunks = chunks;
        this.parses = parses;
        this.ingest = ingest;
    }

    /**
     * 分页查询文档，并把任务进度合并进响应。
     *
     * @param kbId    知识库
     * @param keyword 标题关键字，可空
     * @param current 页码
     * @param size    每页条数
     * @return 一页文档
     */
    public PageResult<RagDocumentResp> page(Long kbId, String keyword, long current, long size) {
        requireKb(kbId);
        PageResult<RagDocument> page = documents.page(kbId, keyword, current, size);
        // 一页最多 100 条，逐条查任务可接受；此处不做批量优化以免过度设计
        Map<Long, RagIngestTask> taskCache = new HashMap<>();
        List<RagDocumentResp> rows = page.getRecords().stream()
                .map(doc -> toResp(doc, tasks.findByDocId(doc.getId()).orElse(null)))
                .toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 上传并异步入库。
     *
     * @param kbId     知识库
     * @param fileName 文件名
     * @param bytes    内容
     * @return 已受理的文档
     */
    public RagDocumentResp submit(Long kbId, String fileName, byte[] bytes) {
        RagDocument doc = ingest.submit(kbId, fileName, bytes);
        return toResp(doc, tasks.findByDocId(doc.getId()).orElse(null));
    }

    /**
     * 重试失败任务。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 重置后的文档
     */
    public RagDocumentResp retry(Long kbId, Long docId) {
        requireDoc(kbId, docId);
        RagDocument doc = ingest.retry(docId);
        return toResp(doc, tasks.findByDocId(docId).orElse(null));
    }

    /**
     * 删除文档。解析文本产物在此一并清掉，避免残留全文；
     * 切片 / 向量 / 原文件仍由入库用例按既有顺序级联。
     *
     * @param kbId  知识库
     * @param docId 文档
     */
    public void remove(Long kbId, Long docId) {
        requireDoc(kbId, docId);
        ingest.remove(docId);
        parses.deleteByDocId(docId);
    }

    /**
     * 查看某文档的全部切片，按序号升序。
     * <p>
     * 只做只读查询；单条切片的正文/章节路径编辑走
     * {@link RagDocumentInspectAppService#updateChunk}，且只重写该片向量。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 切片列表；尚未入库完成时为空列表
     */
    public List<RagChunkResp> listChunks(Long kbId, Long docId) {
        requireDoc(kbId, docId);
        return chunks.listByDocId(docId).stream().map(this::toChunkResp).toList();
    }

    /**
     * 按新的片长重新切片并重跑入库（异步）。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @param req   片长与重叠
     * @return 已受理的文档，status=PENDING
     */
    public RagDocumentResp rechunk(Long kbId, Long docId, RagRechunkReq req) {
        requireDoc(kbId, docId);
        RagDocument doc = ingest.rechunk(docId, req.getChunkSize(), req.getChunkOverlap());
        return toResp(doc, tasks.findByDocId(docId).orElse(null));
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

    private RagDocumentResp toResp(RagDocument doc, RagIngestTask task) {
        RagDocumentResp resp = new RagDocumentResp();
        resp.setId(doc.getId());
        resp.setKbId(doc.getKbId());
        resp.setTitle(doc.getTitle());
        resp.setSourceUri(doc.getSourceUri());
        resp.setMime(doc.getMime());
        resp.setByteSize(doc.getByteSize());
        resp.setChunkCount(doc.getChunkCount());
        resp.setStatus(doc.getStatus());
        resp.setErrorMsg(doc.getErrorMsg());
        resp.setVersion(doc.getVersion());
        resp.setCreateTime(doc.getCreateTime());
        resp.setUpdateTime(doc.getUpdateTime());
        if (task != null) {
            resp.setTaskStatus(task.getStatus());
            resp.setStage(task.getStage());
            resp.setProgress(task.getProgress());
            resp.setAttempts(task.getAttempts());
            resp.setStages(toStageResps(task.getStages()));
        }
        return resp;
    }

    /**
     * 任务阶段摘要转响应。成功后阶段条仍要靠这份列表展示，为空时前端显示「未记录」。
     *
     * @param stages 任务行里的五步摘要
     * @return 响应列表；无摘要时为空列表
     */
    private List<RagStageResp> toStageResps(List<RagIngestStage> stages) {
        if (stages == null || stages.isEmpty()) {
            return List.of();
        }
        return stages.stream().map(stage -> {
            RagStageResp resp = new RagStageResp();
            resp.setStage(stage.getStage());
            resp.setStatus(stage.getStatus());
            resp.setElapsedMs(stage.getElapsedMs());
            resp.setSummary(stage.getSummary());
            return resp;
        }).toList();
    }

    /**
     * 切片领域对象转响应。
     *
     * @param chunk 切片
     * @return 只含展示所需字段的响应
     */
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
}
