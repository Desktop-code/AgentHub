package com.agenthub.rag.infra.milvus;

import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.service.RagCollectionName;
import com.agenthub.rag.domain.service.RagCollectionSchema;
import com.agenthub.spi.rag.ChunkVector;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import com.agenthub.spi.rag.SearchResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 Milvus 版知识库存储。所属域：rag 的 infra。
 * <p>
 * 建集策略（重要）：
 * <ol>
 *   <li>优先建 dense + BM25 sparse 集合（{@code content} 开 chinese analyzer，挂 BM25 Function）；</li>
 *   <li>若 Milvus 不支持 sparse（本机 Lite 会报 104 data type not supported），降级为仅 dense；</li>
 *   <li>两条路都失败才抛 {@link RagStoreException}，由应用服务把 KB 标成 INIT_FAILED。</li>
 * </ol>
 * 实际能力回写到 {@link RagKnowledgeBase#setStoreCapability}，落库后管理员能看到「该库能否做关键词召回」。
 * <p>
 * 写入与检索约定：
 * <ul>
 *   <li>写入用 upsert（按主键 id 覆盖），重灌同一文档不会产生重复行；</li>
 *   <li>{@code content} 在集合里只存截断副本，完整正文以 MySQL {@code rag_chunk.content} 为准；</li>
 *   <li>DENSE_ONLY 能力下**跳过 BM25 检索并返回空列表**，由上层退化为纯向量，绝不因缺 sparse 而失败；</li>
 *   <li>删除先查主键再按 id 批量删——Milvus Lite 的 Delete 过滤只支持主键。</li>
 * </ul>
 * 线程安全：MilvusClientV2 本身线程安全，本类无可变共享状态（除日志），可被多请求并发调用。
 */
@Component
public class LocalMilvusRagProvider implements RagProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalMilvusRagProvider.class);

    /** dense 索引名，便于 describe 时定位 */
    private static final String DENSE_INDEX = "idx_embedding";
    /** sparse 索引名 */
    private static final String SPARSE_INDEX = "idx_sparse_bm25";
    /** BM25 Function 名 */
    private static final String BM25_FUNCTION = "content_bm25";
    /** 检索输出字段：只取渲染需要的最小集合，避免宽行 */
    private static final List<String> OUTPUT_FIELDS = List.of(
            "id", "kb_id", "doc_id", "chunk_id", "title", "source", "content");    /** 主键字段名，删除时按它批量删 */
    private static final String PRIMARY_FIELD = "id";
    /** 软删字段名，检索强制排除 */
    private static final String DELETED_FIELD = "deleted";
    /** 单次删除批量，避免 id 列表过大 */
    private static final int DELETE_BATCH = 512;
    /** 单文档主键数量上限，防止异常数据把删除拖死 */
    private static final long MAX_PRIMARY_IDS = 100_000L;

    private final MilvusClientHolder holder;
    private final RagMilvusProperties properties;

    /**
     * @param holder     懒连接持有者
     * @param properties 是否尝试 BM25、加载超时
     */
    public LocalMilvusRagProvider(MilvusClientHolder holder, RagMilvusProperties properties) {
        this.holder = holder;
        this.properties = properties;
    }

    /**
     * 建集合；已存在则视为成功并只回填名称与能力（幂等，便于「重建」重复点击）。
     *
     * @param kb 知识库，方法会写回 collectionName / storeCapability
     * @throws RagStoreException 维度非法或 Milvus 建集失败
     */
    @Override
    public void createStore(RagKnowledgeBase kb) {
        int dimension = requireDimension(kb);
        String collectionName = resolveCollectionName(kb);
        MilvusClientV2 client = client();
        try {
            if (Boolean.TRUE.equals(client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()))) {
                // 集合已在：不重复建，按现有 schema 探测能力，保证回填值与真实情况一致
                applyResolved(kb, collectionName, detectCapability(client, collectionName));
                ensureLoaded(client, collectionName);
                log.info("[RAG] 集合已存在，跳过创建 kbId={} collection={} capability={}",
                        kb.getId(), collectionName, kb.getStoreCapability());
                return;
            }
            if (properties.isBm25Enabled() && tryCreateBm25(client, collectionName, dimension)) {
                applyResolved(kb, collectionName, "DENSE_BM25");
                ensureLoaded(client, collectionName);
                log.info("[RAG] 已建 dense+BM25 集合 kbId={} collection={} dim={}",
                        kb.getId(), collectionName, dimension);
                return;
            }
            createDenseOnly(client, collectionName, dimension);
            applyResolved(kb, collectionName, "DENSE_ONLY");
            ensureLoaded(client, collectionName);
            log.info("[RAG] 已建仅向量集合 kbId={} collection={} dim={}（当前 Milvus 不支持 BM25）",
                    kb.getId(), collectionName, dimension);
        } catch (RagStoreException e) {
            throw e;
        } catch (Exception e) {
            // 统一收敛成对管理员可读的失败原因；不含连接串密码
            throw new RagStoreException("创建向量集合失败：" + rootMessage(e), e);
        }
    }

    /**
     * 删集合。只删本平台命名规则产出的集合，防止误删同实例上其它业务集合。
     *
     * @param kb 知识库
     * @throws RagStoreException Milvus 不可用或删集失败
     */
    @Override
    public void dropStore(RagKnowledgeBase kb) {
        String collectionName = kb == null ? null : kb.getCollectionName();
        if (collectionName == null || collectionName.isBlank()) {
            return;
        }
        if (!RagCollectionName.ownedByPlatform(collectionName)) {
            // 名字不合规则说明不是本平台建的，拒绝删除，交给人工确认
            throw new RagStoreException("集合名不受平台管理，拒绝删除：" + collectionName);
        }
        MilvusClientV2 client = client();
        try {
            if (!Boolean.TRUE.equals(client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()))) {
                return;
            }
            client.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
            log.info("[RAG] 已删除集合 kbId={} collection={}", kb.getId(), collectionName);
        } catch (Exception e) {
            throw new RagStoreException("删除向量集合失败：" + rootMessage(e), e);
        }
    }

    /**
     * 写入切片向量。按主键 upsert，重复调用幂等。
     * <p>
     * 不写 sparse 字段：BM25 由 Milvus 的 Function 在插入时依据 content 自动生成，
     * 因此 DENSE_ONLY 集合也能走同一条写入路径，不需要分支。
     *
     * @param kb   知识库
     * @param rows 切片
     * @throws RagStoreException 维度不符或写入失败
     */
    @Override
    public void upsertChunks(RagKnowledgeBase kb, List<ChunkVector> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String collectionName = requireCollectionName(kb);
        int dimension = requireDimension(kb);
        MilvusClientV2 client = client();
        ensureLoaded(client, collectionName);

        List<JsonObject> data = new ArrayList<>(rows.size());
        for (ChunkVector row : rows) {
            if (row == null || row.getId() == null) {
                throw new RagStoreException("切片缺少主键，无法写入向量库");
            }
            float[] vector = row.getEmbedding();
            if (vector == null) {
                throw new RagStoreException("切片 " + row.getId() + " 缺少向量");
            }
            // 维度不符必须立刻失败：写进去的错维向量会让后续检索结果全是噪声
            if (vector.length != dimension) {
                throw new RagStoreException("切片 " + row.getId() + " 向量维度 " + vector.length
                        + " 与知识库维度 " + dimension + " 不一致");
            }
            data.add(toJson(row, vector));
        }
        try {
            client.upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .build());
            log.info("[RAG] 写入向量 kbId={} collection={} rows={} capability={}",
                    kb.getId(), collectionName, data.size(), kb.getStoreCapability());
        } catch (Exception e) {
            throw new RagStoreException("写入向量失败：" + rootMessage(e), e);
        }
    }

    /**
     * 按文档删除向量。先查主键再分批按 id 删。
     *
     * @param kb    知识库
     * @param docId 文档 id
     * @throws RagStoreException Milvus 不可用或删除失败
     */
    @Override
    public void deleteByDocId(RagKnowledgeBase kb, Long docId) {
        if (docId == null) {
            return;
        }
        String collectionName = requireCollectionName(kb);
        MilvusClientV2 client = client();
        ensureLoaded(client, collectionName);
        List<Object> ids;
        try {
            ids = queryPrimaryIdsByDocId(client, collectionName, docId);
        } catch (Exception e) {
            throw new RagStoreException("查询待删除向量失败：" + rootMessage(e), e);
        }
        if (ids.isEmpty()) {
            log.info("[RAG] 无向量可删 kbId={} docId={}", kb.getId(), docId);
            return;
        }
        try {
            for (int from = 0; from < ids.size(); from += DELETE_BATCH) {
                List<Object> part = ids.subList(from, Math.min(from + DELETE_BATCH, ids.size()));
                client.delete(DeleteReq.builder()
                        .collectionName(collectionName)
                        .ids(new ArrayList<>(part))
                        .build());
            }
            log.info("[RAG] 已删除向量 kbId={} docId={} rows={}", kb.getId(), docId, ids.size());
        } catch (Exception e) {
            throw new RagStoreException("删除向量失败：" + rootMessage(e), e);
        }
    }

    /**
     * 向量检索。按 KB 隔离：强制 {@code kb_id} 过滤 + {@code deleted == 0}。
     *
     * @param kb      知识库
     * @param query   检索句（仅用于日志与结果回填，不参与向量计算）
     * @param topK    召回条数
     * @param vectors 查询向量
     * @return 命中，按相似度降序
     */
    @Override
    public List<SearchResult> searchVector(RagKnowledgeBase kb, String query, int topK, float[] vectors) {
        if (vectors == null || vectors.length == 0) {
            throw new RagStoreException("查询向量为空，无法检索");
        }
        int dimension = requireDimension(kb);
        if (vectors.length != dimension) {
            throw new RagStoreException("查询向量维度 " + vectors.length + " 与知识库维度 " + dimension + " 不一致");
        }
        String collectionName = requireCollectionName(kb);
        MilvusClientV2 client = client();
        ensureLoaded(client, collectionName);
        SearchReq.SearchReqBuilder builder = SearchReq.builder()
                .collectionName(collectionName)
                .annsField(RagCollectionSchema.FIELD_EMBEDDING)
                .data(List.of(new FloatVec(toList(vectors))))
                .topK(Math.max(1, topK))
                .metricType(IndexParam.MetricType.IP)
                .outputFields(OUTPUT_FIELDS);
        String filter = buildFilter(kb.getId());
        if (!filter.isEmpty()) {
            builder.filter(filter);
        }
        try {
            SearchResp resp = client.search(builder.build());
            return toResults(resp, kb.getId(), "vector");
        } catch (Exception e) {
            throw new RagStoreException("向量检索失败：" + rootMessage(e), e);
        }
    }

    /**
     * BM25 全文检索。能力为 DENSE_ONLY 时直接返回空列表，让上层退化为纯向量。
     *
     * @param kb    知识库
     * @param query 检索句
     * @param topK  召回条数
     * @return 命中；不支持或无命中均为空列表
     */
    @Override
    public List<SearchResult> searchBm25(RagKnowledgeBase kb, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 能力不足不算错误：DENSE_ONLY 是合法状态（Milvus Lite），直接退化
        if (!"DENSE_BM25".equals(capabilityOf(kb))) {
            return List.of();
        }
        String collectionName = requireCollectionName(kb);
        MilvusClientV2 client = client();
        ensureLoaded(client, collectionName);
        SearchReq.SearchReqBuilder builder = SearchReq.builder()
                .collectionName(collectionName)
                .annsField(RagCollectionSchema.FIELD_SPARSE)
                .data(List.of(new EmbeddedText(query.trim())))
                .topK(Math.max(1, topK))
                .metricType(IndexParam.MetricType.BM25)
                .outputFields(OUTPUT_FIELDS);
        String filter = buildFilter(kb.getId());
        if (!filter.isEmpty()) {
            builder.filter(filter);
        }
        try {
            return toResults(client.search(builder.build()), kb.getId(), "bm25");
        } catch (Exception e) {
            // BM25 是增强项：失败只降级为纯向量，不让整次问答失败
            log.warn("[RAG] BM25 检索失败，退化为纯向量 kbId={} reason={}", kb.getId(), rootMessage(e));
            return List.of();
        }
    }

    /**
     * 探测集合能力：有 sparse 字段且类型是 SparseFloatVector 才算 DENSE_BM25。
     *
     * @param kb 知识库
     * @return DENSE_BM25 / DENSE_ONLY
     */
    @Override
    public String probeCapability(RagKnowledgeBase kb) {
        if (kb == null || kb.getCollectionName() == null || kb.getCollectionName().isBlank()) {
            return "DENSE_ONLY";
        }
        return detectCapability(client(), kb.getCollectionName());
    }

    /**
     * 集合统计：切片总数与软删数，供「重建前确认」与排障。
     *
     * @param kb 知识库
     * @return 统计；无集合或查询失败时为空 Map
     */
    @Override
    public Map<String, Object> stats(RagKnowledgeBase kb) {
        if (kb == null || kb.getCollectionName() == null || kb.getCollectionName().isBlank()) {
            return Map.of();
        }
        MilvusClientV2 client = client();
        ensureLoaded(client, kb.getCollectionName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capability", capabilityOf(kb));
        try {
            QueryResp all = client.query(QueryReq.builder()
                    .collectionName(kb.getCollectionName())
                    .filter("kb_id == " + kb.getId())
                    .outputFields(List.of(PRIMARY_FIELD))
                    .limit(16384)
                    .build());
            out.put("rows", all == null || all.getQueryResults() == null ? 0 : all.getQueryResults().size());
        } catch (Exception e) {
            log.warn("[RAG] 统计集合行数失败 kbId={} reason={}", kb.getId(), rootMessage(e));
        }
        return out;
    }

    /**
     * 尝试建 BM25 集合。
     * <p>
     * 不用异常类型区分「不支持」与「其它错误」——Milvus Lite 报的是 code=0 的 MilvusException，
     * 类型不可靠；这里失败一律降级为纯向量，但把原因写日志，便于事后判断是不是配置问题。
     *
     * @return true 表示建成功
     */
    private boolean tryCreateBm25(MilvusClientV2 client, String collectionName, int dimension) {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        fields.add(RagCollectionSchema.idField());
        fields.add(RagCollectionSchema.vectorField(dimension));
        fields.add(RagCollectionSchema.analyzedContentField());
        fields.add(RagCollectionSchema.sparseField());
        fields.addAll(RagCollectionSchema.scalarFields());

        CreateCollectionReq.Function bm25 = CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name(BM25_FUNCTION)
                .inputFieldNames(List.of(RagCollectionSchema.FIELD_CONTENT))
                .outputFieldNames(List.of(RagCollectionSchema.FIELD_SPARSE))
                .build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                // 平台字段模板定死，禁止动态字段，避免写入端偷偷塞未声明列
                .enableDynamicField(Boolean.FALSE)
                .fieldSchemaList(fields)
                .functionList(List.of(bm25))
                .build();

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName(RagCollectionSchema.FIELD_EMBEDDING)
                .indexName(DENSE_INDEX)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                // 写入前已 L2 归一化，IP 等价余弦
                .metricType(IndexParam.MetricType.IP)
                .build());
        indexes.add(IndexParam.builder()
                .fieldName(RagCollectionSchema.FIELD_SPARSE)
                .indexName(SPARSE_INDEX)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());

        try {
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description("AgentHub knowledge base " + collectionName)
                    .collectionSchema(schema)
                    .indexParams(indexes)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("[RAG] 建 BM25 集合失败，降级为仅向量 collection={} reason={}",
                    collectionName, rootMessage(e));
            return false;
        }
    }

    /**
     * 建仅 dense 集合（Milvus Lite / 未开全文的实例）。
     */
    private void createDenseOnly(MilvusClientV2 client, String collectionName, int dimension) {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        fields.add(RagCollectionSchema.idField());
        fields.add(RagCollectionSchema.vectorField(dimension));
        fields.add(RagCollectionSchema.plainContentField());
        fields.addAll(RagCollectionSchema.scalarFields());

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(Boolean.FALSE)
                .fieldSchemaList(fields)
                .build();

        IndexParam dense = IndexParam.builder()
                .fieldName(RagCollectionSchema.FIELD_EMBEDDING)
                .indexName(DENSE_INDEX)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.IP)
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("AgentHub knowledge base " + collectionName + " (dense only)")
                .collectionSchema(schema)
                .indexParams(List.of(dense))
                .build());
    }

    /**
     * 按 describe 结果判定能力。schema 读取失败按 DENSE_ONLY 处理，宁可保守。
     */
    private String detectCapability(MilvusClientV2 client, String collectionName) {
        try {
            DescribeCollectionResp resp = client.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collectionName).build());
            if (resp == null || resp.getCollectionSchema() == null
                    || resp.getCollectionSchema().getFieldSchemaList() == null) {
                return "DENSE_ONLY";
            }
            for (CreateCollectionReq.FieldSchema field : resp.getCollectionSchema().getFieldSchemaList()) {
                if (field != null && RagCollectionSchema.FIELD_SPARSE.equals(field.getName())
                        && DataType.SparseFloatVector.equals(field.getDataType())) {
                    return "DENSE_BM25";
                }
            }
            return "DENSE_ONLY";
        } catch (Exception e) {
            log.warn("[RAG] 探测集合能力失败 collection={} reason={}", collectionName, rootMessage(e));
            return "DENSE_ONLY";
        }
    }

    /**
     * 确保集合已加载。已加载则跳过；未加载按配置超时同步加载。
     * <p>
     * 注意：SDK 的 timeout 单位是毫秒，误传秒会立刻报 Load collection timeout。
     */
    private void ensureLoaded(MilvusClientV2 client, String collectionName) {
        try {
            Boolean loaded = client.getLoadState(
                    GetLoadStateReq.builder().collectionName(collectionName).build());
            if (Boolean.TRUE.equals(loaded)) {
                return;
            }
            client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .sync(Boolean.TRUE)
                    .timeout(Math.max(properties.getLoadTimeoutMs(), 1000L))
                    .build());
        } catch (Exception e) {
            throw new RagStoreException("集合加载失败：" + rootMessage(e), e);
        }
    }

    /**
     * 拼接检索过滤条件。KB 隔离是硬约束，必须永远带上；deleted 软删同样强制排除。
     *
     * @param kbId 知识库 id
     * @return 过滤表达式；kbId 为空时只带 deleted 条件
     */
    static String buildFilter(Long kbId) {
        if (kbId == null) {
            return DELETED_FIELD + " == 0";
        }
        return "kb_id == " + kbId + " && " + DELETED_FIELD + " == 0";
    }

    /**
     * 组装一条 Milvus 行。content 截断到字段上限，完整正文在 MySQL。
     */
    private static JsonObject toJson(ChunkVector row, float[] vector) {
        JsonObject json = new JsonObject();
        json.addProperty(RagCollectionSchema.FIELD_ID, row.getId());
        json.addProperty("kb_id", row.getKbId());
        json.addProperty("doc_id", row.getDocId());
        json.addProperty("chunk_id", row.getChunkId() == null ? row.getId() : row.getChunkId());
        json.addProperty("tenant_id", row.getTenantId() == null ? 0L : row.getTenantId());
        json.addProperty("dept_id", row.getDeptId() == null ? 0L : row.getDeptId());
        json.addProperty("version", row.getVersion() == null ? 1L : row.getVersion());
        json.addProperty("content_hash", cut(row.getContentHash(), 64));
        json.addProperty("deleted", row.getDeleted() == null ? 0 : row.getDeleted());
        json.addProperty("effective_from", row.getEffectiveFrom() == null ? 0L : row.getEffectiveFrom());
        json.addProperty("expire_time", row.getExpireTime() == null ? 0L : row.getExpireTime());
        json.addProperty("create_by", row.getCreateBy() == null ? 0L : row.getCreateBy());
        json.addProperty("owner_user_id", row.getOwnerUserId() == null ? 0L : row.getOwnerUserId());
        json.addProperty("title", cut(row.getTitle(), 255));
        json.addProperty("source", cut(row.getSource(), 255));
        json.addProperty(RagCollectionSchema.FIELD_CONTENT,
                cut(row.getContent(), RagCollectionSchema.CONTENT_MAX_LENGTH));
        json.add(RagCollectionSchema.FIELD_EMBEDDING, toJsonArray(vector));
        return json;
    }

    /**
     * 从 Milvus 检索响应转成命中对象。完整正文由 rag 域用 MySQL 回填，这里只带集合里的截断副本。
     */
    private static List<SearchResult> toResults(SearchResp resp, Long kbId, String hitBy) {
        List<SearchResult> out = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null) {
            return out;
        }
        for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
            if (group == null) {
                continue;
            }
            for (SearchResp.SearchResult item : group) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> entity = item.getEntity();
                SearchResult hit = new SearchResult();
                hit.setKbId(kbId);
                hit.setChunkId(asLong(entity, "chunk_id", "id"));
                hit.setDocId(asLong(entity, "doc_id"));
                hit.setContent(asString(entity, RagCollectionSchema.FIELD_CONTENT));
                hit.setTitle(asString(entity, "title"));
                hit.setSource(asString(entity, "source"));
                hit.setHitBy(hitBy);
                if ("bm25".equals(hitBy)) {
                    hit.setBm25Score(item.getScore() == null ? null : item.getScore().doubleValue());
                } else {
                    hit.setVectorScore(item.getScore() == null ? null : item.getScore().doubleValue());
                }
                out.add(hit);
            }
        }
        return out;
    }

    /**
     * 按 docId 查主键。Milvus Lite 的 Delete 只接受主键，因此必须先查再删。
     */
    private static List<Object> queryPrimaryIdsByDocId(MilvusClientV2 client, String collectionName, Long docId) {
        List<Object> ids = new ArrayList<>();
        long offset = 0;
        while (true) {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("doc_id == " + docId)
                    .outputFields(List.of(PRIMARY_FIELD))
                    .offset(offset)
                    .limit(DELETE_BATCH)
                    .build());
            if (resp == null || resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) {
                break;
            }
            int got = 0;
            for (QueryResp.QueryResult item : resp.getQueryResults()) {
                Object id = item == null || item.getEntity() == null ? null : item.getEntity().get("id");
                if (id != null) {
                    ids.add(id);
                    got++;
                }
            }
            if (got < DELETE_BATCH) {
                break;
            }
            offset += got;
            if (offset > MAX_PRIMARY_IDS) {
                log.warn("[RAG] docId={} 主键过多，已截断在 {}", docId, MAX_PRIMARY_IDS);
                break;
            }
        }
        return ids;
    }

    /**
     * 集合名：优先用已落库的，其次按 kb_id 生成。
     */
    private String resolveCollectionName(RagKnowledgeBase kb) {
        if (kb == null) {
            throw new RagStoreException("知识库为空，无法建集合");
        }
        String existing = kb.getCollectionName();
        if (existing != null && !existing.isBlank()) {
            if (!RagCollectionName.ownedByPlatform(existing)) {
                throw new RagStoreException("集合名不受平台管理，拒绝建集：" + existing);
            }
            return existing;
        }
        return RagCollectionName.of(kb.getId());
    }

    private static String requireCollectionName(RagKnowledgeBase kb) {
        String name = kb == null ? null : kb.getCollectionName();
        if (name == null || name.isBlank()) {
            throw new RagStoreException("知识库尚未初始化集合，无法读写向量");
        }
        return name;
    }

    /**
     * 能力取值：优先用已探测结果，为空时按 DENSE_ONLY 保守处理。
     */
    private static String capabilityOf(RagKnowledgeBase kb) {
        String capability = kb == null ? null : kb.getStoreCapability();
        return capability == null || capability.isBlank() ? "DENSE_ONLY" : capability;
    }

    private static void applyResolved(RagKnowledgeBase kb, String collectionName, String capability) {
        kb.setCollectionName(collectionName);
        kb.setStoreCapability(capability);
    }

    /**
     * 维度必须 > 0。0 或负数是 Embedding 模型元数据缺失的信号，写读前就拦住。
     */
    private static int requireDimension(RagKnowledgeBase kb) {
        Integer dimension = kb == null ? null : kb.getVectorDimension();
        if (dimension == null || dimension <= 0) {
            throw new RagStoreException("向量维度缺失或非法");
        }
        return dimension;
    }

    /**
     * 取客户端；未启动/连不上时转成 RagStoreException，调用方只处理一种异常。
     */
    private MilvusClientV2 client() {
        try {
            return holder.client();
        } catch (IllegalStateException e) {
            throw new RagStoreException(e.getMessage(), e);
        }
    }

    private static Long asLong(Map<String, Object> entity, String... keys) {
        if (entity == null) {
            return null;
        }
        for (String key : keys) {
            Object value = entity.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // 换下一个键
                }
            }
        }
        return null;
    }

    private static String asString(Map<String, Object> entity, String key) {
        Object value = entity == null ? null : entity.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private static JsonArray toJsonArray(float[] vector) {
        JsonArray array = new JsonArray();
        for (float v : vector) {
            array.add(v);
        }
        return array;
    }

    /**
     * 取最内层异常消息，避免把 gRPC 的长堆栈写进 error_msg。
     */
    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}


