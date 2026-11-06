package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.text.TextClip;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.rag.api.dto.RagKbCreateReq;
import com.agenthub.rag.api.dto.RagKbPageReq;
import com.agenthub.rag.api.dto.RagKbResp;
import com.agenthub.rag.api.dto.RagKbUpdateReq;
import com.agenthub.rag.domain.model.RagIsolationMode;
import com.agenthub.rag.domain.model.RagKbQuery;
import com.agenthub.rag.domain.model.RagKbStatus;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.model.RagStoreType;
import com.agenthub.rag.domain.model.RagVisibility;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.RagCollectionName;
import com.agenthub.rag.domain.service.RagKbNamePolicy;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识库用例。所属域：rag。一期职责仅限「建库 + 初始化 Collection + CRUD + 重建 + 删库」。
 * <p>
 * 事务边界（重要）：
 * <ul>
 *   <li>建库：先落 MySQL（INIT_FAILED）拿到 id，再建 Milvus 集合，最后回写状态。
 *       <b>不把 Milvus 调用包进数据库事务</b>——远程调用不可回滚，把长耗时的建集放进事务会长期占住连接。
 *       进程在建集中途挂掉时，库里留下 INIT_FAILED 记录，管理员可点「重建」恢复。</li>
 *   <li>删库：先删 Milvus 集合，成功后再逻辑删 MySQL；集合删除失败则保留记录，避免产生孤儿集合。</li>
 * </ul>
 * 约束：本类不读模型 Key / Base URL，Embedding 只按 id 交给 ②③ 期的模型中心解析。
 */
@Service
public class RagKnowledgeBaseAppService {

    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeBaseAppService.class);

    private final RagKnowledgeBaseRepository kbs;
    private final RagKbNamePolicy names;
    private final RagModelLookup models;
    private final ModelInstanceRepository instances;
    private final RagProvider provider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final RagKnowledgeBaseTx tx;

    /**
     * @param kbs             知识库仓储
     * @param names           未删名称唯一
     * @param models          Embedding / Rerank 实例校验与维度解析
     * @param instances       仅用于列表回填模型展示名
     * @param provider        建集 / 删集（在事务外调用）
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志（update 与主表操作直接写）
     * @param tx              写库阶段（事务内：落库、回写状态、级联删、日志）
     */
    public RagKnowledgeBaseAppService(RagKnowledgeBaseRepository kbs, RagKbNamePolicy names,
                                      RagModelLookup models, ModelInstanceRepository instances,
                                      RagProvider provider, OperatorContext operatorContext,
                                      OperLogWriter operLogWriter, RagKnowledgeBaseTx tx) {
        this.kbs = kbs;
        this.names = names;
        this.models = models;
        this.instances = instances;
        this.provider = provider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.tx = tx;
    }

    /**
     * @param req 分页与筛选
     * @return 一页知识库
     */
    public PageResult<RagKbResp> page(RagKbPageReq req) {
        RagKbQuery query = new RagKbQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        query.setStatus(normalizeOrNull(req.getStatus()));
        query.setVisibility(normalizeOrNull(req.getVisibility()));
        PageResult<RagKnowledgeBase> page = kbs.page(query);
        // 同一页里模型实例可能重复引用，先建小缓存，避免 N+1 查询
        Map<Long, String> nameCache = new HashMap<>();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(kb -> toResp(kb, nameCache)).toList());
    }

    /**
     * @param id 主键
     * @return 详情
     * @throws BizException 不存在时 NOT_FOUND
     */
    public RagKbResp get(Long id) {
        return toResp(require(id), new HashMap<>());
    }

    /**
     * 建库：校验 → 落 MySQL（INIT_FAILED）→ 建 Collection → 回写 READY / INIT_FAILED。
     * <p>
     * 建集失败不抛异常给前端，而是返回带 errorMsg 的详情：管理员看到的是「库已建但初始化失败」，
     * 可以在列表里直接点重建，而不是重填一遍表单。
     * <p>
     * 事务边界：落库与状态回写各自一个短事务；{@code createStore} 是外部调用，跑在事务**之外**，
     * 避免 Milvus 慢时长时间占着数据库连接（库行先以 INIT_FAILED 提交，正是为了能这样拆）。
     *
     * @param req 创建参数
     * @return 创建的详情（含最终状态）
     */
    public RagKbResp create(RagKbCreateReq req) {
        RagStoreType storeType = parseStoreType(req.getStoreType());
        RagIsolationMode isolation = parseIsolation(req.getIsolationMode());
        RagVisibility visibility = parseVisibility(req.getVisibility());

        String name = RagKbChecks.requiredText(req.getName(), RagKbChecks.NAME_MAX, "名称");
        names.assertUnique(name, null);

        // 维度从模型元数据带出并就此锁定；后续换模型只能新建库
        ModelInstance embedding = models.requireEmbedding(req.getEmbeddingModelId());
        int dimension = models.resolveDimension(embedding, req.getVectorDimension());
        ModelInstance rerank = models.requireRerank(req.getRerankModelId());

        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setTenantId(0L);
        kb.setName(name);
        kb.setDescription(RagKbChecks.optionalText(req.getDescription(), RagKbChecks.DESCRIPTION_MAX, "描述"));
        kb.setStoreType(storeType.getCode());
        kb.setEmbeddingModelId(embedding.getId());
        kb.setVectorDimension(dimension);
        kb.setRerankModelId(rerank == null ? null : rerank.getId());
        kb.setIsolationMode(isolation.getCode());
        kb.setVisibility(visibility.getCode());
        kb.setRetrievalConfig(RagKbChecks.configJson(req.getRetrievalConfig(),
                RagKbChecks.DEFAULT_RETRIEVAL_CONFIG, "检索参数"));
        kb.setIngestConfig(RagKbChecks.configJson(req.getIngestConfig(),
                RagKbChecks.DEFAULT_INGEST_CONFIG, "入库参数"));
        // 先按失败落库：集合没建成功之前，这条记录不能被当成可用库
        kb.setStatus(RagKbStatus.INIT_FAILED.getCode());

        Long operatorId = operatorContext.requireUserId();
        Long id = tx.insert(kb, operatorId);
        kb.setId(id);
        // 集合名在落库后生成：依赖自增 id，且写进库里便于运维直接对着 Milvus 核对
        kb.setCollectionName(RagCollectionName.of(id));

        boolean ok = initializeStore(kb, operatorId);
        tx.log("create", String.valueOf(id), "创建知识库 " + name + (ok ? "，集合初始化成功" : "，集合初始化失败"));
        return toResp(require(id), new HashMap<>());
    }

    /**
     * 改资料与检索参数。Embedding / 维度 / 隔离模式不可改：它们与已建集合绑定。
     *
     * @param id  主键
     * @param req 可改字段
     * @return 更新后的详情
     */
    @Transactional
    public RagKbResp update(Long id, RagKbUpdateReq req) {
        RagKnowledgeBase existing = require(id);
        String name = RagKbChecks.requiredText(req.getName(), RagKbChecks.NAME_MAX, "名称");
        names.assertUnique(name, id);
        RagVisibility visibility = parseVisibility(req.getVisibility());
        // 0 表示清空 Rerank；其它值必须是通过校验的 RERANK 实例
        ModelInstance rerank = (req.getRerankModelId() != null && req.getRerankModelId() == 0L)
                ? null : models.requireRerank(req.getRerankModelId());

        existing.setName(name);
        existing.setDescription(RagKbChecks.optionalText(req.getDescription(), RagKbChecks.DESCRIPTION_MAX, "描述"));
        existing.setVisibility(visibility.getCode());
        existing.setRerankModelId(rerank == null ? null : rerank.getId());
        existing.setRetrievalConfig(RagKbChecks.configJson(req.getRetrievalConfig(),
                existing.getRetrievalConfig() == null ? RagKbChecks.DEFAULT_RETRIEVAL_CONFIG : existing.getRetrievalConfig(),
                "检索参数"));
        existing.setIngestConfig(RagKbChecks.configJson(req.getIngestConfig(),
                existing.getIngestConfig() == null ? RagKbChecks.DEFAULT_INGEST_CONFIG : existing.getIngestConfig(),
                "入库参数"));

        Long operatorId = operatorContext.requireUserId();
        kbs.updateProfile(existing, operatorId);
        operLogWriter.write("rag", "update", "rag_knowledge_base", String.valueOf(id), "更新知识库 " + name);
        return toResp(require(id), new HashMap<>());
    }

    /**
     * 重建集合：删旧（若在）→ 按当前维度重建 → 回写状态。
     * 用途：初始化失败后恢复、Milvus 数据被清理后恢复。
     * <p>
     * 注意：重建会丢弃该库已有向量。② 期后应在有文档时给二次确认或先禁止，本期无入库数据，直接允许。
     * <p>
     * 事务边界同建库：外部建集在事务外，只有状态回写与日志进短事务。
     *
     * @param id 主键
     * @return 重建后的详情
     */
    public RagKbResp rebuild(Long id) {
        RagKnowledgeBase kb = require(id);
        Long operatorId = operatorContext.requireUserId();
        boolean ok = initializeStore(kb, operatorId);
        tx.log("rebuild", String.valueOf(id), "重建知识库集合 " + kb.getName() + (ok ? "，成功" : "，失败"));
        return toResp(require(id), new HashMap<>());
    }

    /**
     * 删库：先删集合再逻辑删记录。
     * <p>
     * 顺序不能反：先删记录会让集合名失去来源，留下无法定位的孤儿集合。
     * 集合删除失败时整体失败，让管理员能看到原因并重试。
     * <p>
     * ② 期起级联清理：该库的文档、切片、入库任务、Agent 绑定一并删掉，
     * 否则会留下指向不存在库的悬空行（检索时靠 kb_id 过滤查不到数据，但排障时非常误导）。
     * <p>
     * 事务边界：{@code dropStore} 在事务外（失败直接抛、库里什么都不改）；级联删除与操作日志进一个短事务。
     *
     * @param id 主键
     */
    public void remove(Long id) {
        RagKnowledgeBase kb = require(id);
        Long operatorId = operatorContext.requireUserId();
        if (kb.getCollectionName() != null && !kb.getCollectionName().isBlank()) {
            try {
                provider.dropStore(kb);
            } catch (RagStoreException e) {
                // 删集失败就停下：库里记录仍在，管理员可重试；不产生「记录没了集合还在」的脏数据
                throw new BizException(ErrorCode.INTERNAL,
                        "删除向量集合失败，知识库未删除，请稍后重试：" + e.getMessage());
            }
        }
        // 落库阶段：先清子表再删主表，顺序由 tx 内部保证
        tx.deleteCascade(id, kb, operatorId);
    }

    /**
     * 建集并回写状态。所有失败都收敛在这里，调用方只看布尔值。
     * <p>网络调用在事务外；状态回写走 {@link RagKnowledgeBaseTx} 的短事务。
     *
     * @param kb         已落库、带 id 与维度的知识库
     * @param operatorId 操作人
     * @return true 表示集合就绪
     */
    private boolean initializeStore(RagKnowledgeBase kb, Long operatorId) {
        try {
            provider.createStore(kb);
            tx.updateInitResult(kb.getId(), kb.getCollectionName(), kb.getStoreCapability(),
                    RagKbStatus.READY.getCode(), null, operatorId);
            log.info("[RAG] 知识库初始化成功 id={} name={} collection={} capability={}",
                    kb.getId(), kb.getName(), kb.getCollectionName(), kb.getStoreCapability());
            return true;
        } catch (RagStoreException e) {
            String reason = TextClip.raw(e.getMessage(), 500);
            tx.updateInitResult(kb.getId(), kb.getCollectionName(), null,
                    RagKbStatus.INIT_FAILED.getCode(), reason, operatorId);
            log.warn("[RAG] 知识库初始化失败 id={} name={} reason={}", kb.getId(), kb.getName(), reason);
            return false;
        } catch (RuntimeException e) {
            // 兜底：Provider 之外的意外（如维度校验 NPE）同样落成初始化失败，不把 500 抛给前端
            String reason = TextClip.raw("初始化异常：" + e.getClass().getSimpleName()
                    + " " + (e.getMessage() == null ? "" : e.getMessage()), 500);
            tx.updateInitResult(kb.getId(), kb.getCollectionName(), null,
                    RagKbStatus.INIT_FAILED.getCode(), reason, operatorId);
            log.error("[RAG] 知识库初始化异常 id={}", kb.getId(), e);
            return false;
        }
    }

    private RagStoreType parseStoreType(String raw) {
        RagStoreType type;
        try {
            type = RagStoreType.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (!type.implemented()) {
            // 枚举保留但未实装：明确拒绝，避免建出没有存储的库
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "一期仅支持 LOCAL_MILVUS，" + type.getCode() + " 未实装");
        }
        return type;
    }

    private RagIsolationMode parseIsolation(String raw) {
        RagIsolationMode mode;
        try {
            mode = RagIsolationMode.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (!mode.availableNow()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "LOGICAL 隔离（多库共集合）二期开放，一期请选 PHYSICAL 或 PUBLIC");
        }
        return mode;
    }

    private RagVisibility parseVisibility(String raw) {
        RagVisibility visibility;
        try {
            visibility = RagVisibility.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (!visibility.availableNow()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "RESTRICTED 可见性依赖授权表，二期开放，一期请选 PUBLIC 或 PRIVATE");
        }
        return visibility;
    }

    /**
     * @param id 主键
     * @return 未删除的知识库
     * @throws BizException 不存在时 NOT_FOUND
     */
    private RagKnowledgeBase require(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "知识库 id 不能为空");
        }
        return kbs.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在"));
    }

    private RagKbResp toResp(RagKnowledgeBase kb, Map<Long, String> nameCache) {
        RagKbResp resp = new RagKbResp();
        resp.setId(kb.getId());
        resp.setName(kb.getName());
        resp.setDescription(kb.getDescription());
        resp.setStoreType(kb.getStoreType());
        resp.setStoreCapability(kb.getStoreCapability());
        resp.setEmbeddingModelId(kb.getEmbeddingModelId());
        resp.setEmbeddingModelName(modelName(kb.getEmbeddingModelId(), nameCache));
        resp.setVectorDimension(kb.getVectorDimension());
        resp.setRerankModelId(kb.getRerankModelId());
        resp.setRerankModelName(modelName(kb.getRerankModelId(), nameCache));
        resp.setIsolationMode(kb.getIsolationMode());
        resp.setVisibility(kb.getVisibility());
        resp.setRetrievalConfig(kb.getRetrievalConfig());
        resp.setIngestConfig(kb.getIngestConfig());
        resp.setStatus(kb.getStatus());
        resp.setErrorMsg(kb.getErrorMsg());
        resp.setCreateTime(kb.getCreateTime());
        resp.setUpdateTime(kb.getUpdateTime());
        return resp;
    }

    /**
     * 模型展示名。实例已被删时返回 null，不让详情整体失败。
     */
    private String modelName(Long instanceId, Map<Long, String> cache) {
        if (instanceId == null) {
            return null;
        }
        if (cache.containsKey(instanceId)) {
            return cache.get(instanceId);
        }
        String name = instances.findById(instanceId).map(ModelInstance::getDisplayName).orElse(null);
        cache.put(instanceId, name);
        return name;
    }

    private static String normalizeOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase();
    }

}
