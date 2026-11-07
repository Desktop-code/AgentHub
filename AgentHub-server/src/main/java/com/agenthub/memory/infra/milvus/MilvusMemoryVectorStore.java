package com.agenthub.memory.infra.milvus;

import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorRow;
import com.agenthub.memory.domain.service.MemoryCollectionName;
import com.agenthub.memory.domain.service.MemoryVectorSchema;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.domain.service.MemoryVectorStoreException;
import com.agenthub.rag.infra.milvus.MilvusClientHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆专用 Milvus 集合初始化。所属域：memory 的 infra。
 * <p>
 * 连接复用 rag 域的 {@link MilvusClientHolder}：同一 Milvus 实例、同一套懒连接与失败重连策略，
 * 禁止在 memory 再建第二套客户端（双客户端会出现两套超时/重连行为，排障时互相干扰）。
 * <p>
 * 幂等语义：
 * <ul>
 *   <li>集合已存在：只校验既有 embedding 维度是否与调用方透传的平台级 {@code embedding_dim} 快照一致，
 *       然后尝试加载；绝不重建、不删数据；</li>
 *   <li>集合不存在：按调用方透传的维度快照建 embedding 字段，建完同步加载；</li>
 *   <li>写入按 {@code memory_id} upsert、删除按主键删，重复执行不产生重复行；</li>
 *   <li>删除集合（{@link #dropCollection(String)}）只服务于受控重建 API，ensure / 写入/ 召回路径永不触达；</li>
 *   <li>任何失败统一收敛为 {@link MemoryVectorStoreException}，由上层降级为 isReady=false / 仅日志。</li>
 * </ul>
 * 线程安全：MilvusClientV2 本身线程安全，本类无可变共享状态；ensure 由上层加锁调用。
 */
@Component
public class MilvusMemoryVectorStore implements MemoryVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusMemoryVectorStore.class);

    /** 单次删除批量：避免一次请求携带过多主键被服务端拒绝 */
    private static final int DELETE_BATCH = 512;

    /**
     * 检索要回传的标量/正文。正文用于「主键回查拿不到正文」时兜底；
     * MySQL 对齐仍是真源，向量侧只提供副本。
     */
    private static final List<String> OUTPUT_FIELDS = List.of(
            MemoryVectorSchema.FIELD_MEMORY_ID,
            MemoryVectorSchema.FIELD_CONTENT,
            MemoryVectorSchema.FIELD_IMPORTANCE,
            MemoryVectorSchema.FIELD_EXPIRED_TIME);

    private final MilvusClientHolder holder;
    /** 建集后同步加载的上限；与 RAG 共用同一配置键，SDK 单位是毫秒 */
    private final long loadTimeoutMs;

    /**
     * @param holder        复用的 Milvus 懒连接持有者
     * @param loadTimeoutMs 集合加载超时（毫秒）
     */
    public MilvusMemoryVectorStore(MilvusClientHolder holder,
                                   @Value("${agenthub.rag.milvus.load-timeout-ms:15000}") long loadTimeoutMs) {
        this.holder = holder;
        this.loadTimeoutMs = Math.max(loadTimeoutMs, 1000L);
    }

    /**
     * 确保记忆集合存在且已加载；已存在时只做维度与字段校验，保证可重复调用。
     * <p>
     * 已存在但缺字段（本刀新增 {@code expired_time / importance / content}）时<b>直接失败</b>：
     * Milvus 不支持原地加字段，静默继续会让后续写入全部被拒，比提前 isReady=false 更糟。
     * 也不在这里 drop 重建——生产数据不允许由代码静默删除。
     *
     * @param collectionName 记忆 collection 名
     * @param dimension      目标维度：平台级 Embedding 最近一次连通测试成功快照 {@code embedding_dim}
     *                       （YAML {@code embedding-dimension} 仅排障对照，不传进来）
     * @throws MemoryVectorStoreException 名/维度非法、Milvus 不可用、维度或字段不一致、建集或加载失败
     */
    @Override
    public void ensureCollection(String collectionName, int dimension) {
        // 参数非法时在出站前拦住，避免向 Milvus 发一个注定失败的建集请求
        if (!StringUtils.hasText(collectionName)) {
            throw new MemoryVectorStoreException("记忆 collection 未配置");
        }
        if (dimension <= 0) {
            throw new MemoryVectorStoreException("记忆向量维度非法：" + dimension);
        }
        MilvusClientV2 client = client();
        try {
            if (Boolean.TRUE.equals(client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()))) {
                // 已存在：不重建；但维度/字段不一致会让后续写入永久失败，必须提前暴露
                verifyExistingSchema(client, collectionName, dimension);
                log.debug("[MEMORY] 记忆集合已存在，跳过创建 collection={} dim={}", collectionName, dimension);
            } else {
                createCollection(client, collectionName, dimension);
                log.info("[MEMORY] 已创建记忆向量集合 collection={} dim={}", collectionName, dimension);
            }
            ensureLoaded(client, collectionName);
        } catch (MemoryVectorStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new MemoryVectorStoreException("记忆向量集合初始化失败：" + rootMessage(e), e);
        }
    }

    /**
     * 读取既有集合的 embedding 维度；集合不存在或 schema 读不到返回 null（只记 debug）。
     *
     * @param collectionName 记忆 collection 名
     * @return 维度；无法判断为 null
     */
    @Override
    public Integer embeddingDimension(String collectionName) {
        if (!StringUtils.hasText(collectionName)) {
            return null;
        }
        DescribeCollectionResp resp = describe(client(), collectionName);
        if (resp == null || resp.getCollectionSchema() == null
                || resp.getCollectionSchema().getFieldSchemaList() == null) {
            return null;
        }
        return embeddingDimensionOf(resp.getCollectionSchema().getFieldSchemaList());
    }

    /**
     * 受控删除记忆集合。只允许 {@code agenthub_memory_*} 命名空间，集合不存在视为成功。
     * <p>
     * 本方法只由受控重建 API 调用；ensure / 写入 / 召回路径不得触达，避免出现「静默 DROP」。
     *
     * @param collectionName 记忆 collection 名
     * @throws MemoryVectorStoreException 名不受管理、Milvus 不可用或删除失败
     */
    @Override
    public void dropCollection(String collectionName) {
        if (!StringUtils.hasText(collectionName)) {
            throw new MemoryVectorStoreException("记忆 collection 未配置");
        }
        // 命名空间校验：记忆重建只能碰 agenthub_memory_*，绝不能误删 RAG 的 agenthub_kb_*
        if (!MemoryCollectionName.isManaged(collectionName)) {
            throw new MemoryVectorStoreException("集合名不在记忆命名空间内，拒绝删除：" + collectionName);
        }
        MilvusClientV2 client = client();
        try {
            if (!Boolean.TRUE.equals(client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()))) {
                log.info("[MEMORY] 重建前集合不存在，跳过删除 collection={}", collectionName);
                return;
            }
            client.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
            log.info("[MEMORY] 受控重建已删除集合 collection={}", collectionName);
        } catch (Exception e) {
            throw new MemoryVectorStoreException("删除记忆集合失败：" + rootMessage(e), e);
        }
    }

    /**
     * 按调用方透传维度（平台级 Embedding 快照）建集合。
     * 字段模板固定，禁止开启动态字段（防止写入端偷偷塞未声明列）。
     */
    private void createCollection(MilvusClientV2 client, String collectionName, int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(Boolean.FALSE)
                .fieldSchemaList(MemoryVectorSchema.fields(dimension))
                .build();
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("AgentHub long memory " + collectionName)
                .collectionSchema(schema)
                .indexParams(List.of(MemoryVectorSchema.vectorIndex()))
                .build());
    }

    /**
     * 校验既有集合：先比维度，再比字段齐全度。
     * <p>
     * 读不到 schema 时只记日志放过（不因 describe 失败挡住加载，后续写入阶段会给出真实错误）；
     * 读到不一致则失败——这类集合的写入必然被 Milvus 拒绝，或（更糟）字段缺失导致语义错误。
     */
    private void verifyExistingSchema(MilvusClientV2 client, String collectionName, int expected) {
        DescribeCollectionResp resp = describe(client, collectionName);
        if (resp == null || resp.getCollectionSchema() == null
                || resp.getCollectionSchema().getFieldSchemaList() == null) {
            return;
        }
        List<CreateCollectionReq.FieldSchema> fields = resp.getCollectionSchema().getFieldSchemaList();
        Integer actual = embeddingDimensionOf(fields);
        if (actual != null && actual > 0 && actual != expected) {
            // 维度真源是平台级 Embedding 最近一次连通测试快照；不一致绝不静默改维，走受控重建 API
            throw new MemoryVectorStoreException("集合 " + collectionName + " 向量维度 " + actual
                    + " 与平台级 Embedding 维度快照 " + expected + " 不一致，需执行受控重建");
        }
        // 字段缺失必须暴露：老集合没有 expired_time/importance/content，检索过滤与召回兜底都会失效
        Set<String> present = new LinkedHashSet<>();
        for (CreateCollectionReq.FieldSchema field : fields) {
            if (field != null && field.getName() != null) {
                present.add(field.getName());
            }
        }
        List<String> missing = MemoryVectorSchema.missingFields(present);
        if (!missing.isEmpty()) {
            throw new MemoryVectorStoreException("集合 " + collectionName + " 缺少字段 " + missing
                    + "，Milvus 不支持原地加字段，需按文档人工重建（不会自动删除数据）");
        }
    }

    /**
     * @return describe 响应；失败返回 null（只记 warn，不挡加载）
     */
    private DescribeCollectionResp describe(MilvusClientV2 client, String collectionName) {
        try {
            return client.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collectionName).build());
        } catch (Exception e) {
            log.warn("[MEMORY] 读取记忆集合 schema 失败，跳过校验 collection={} reason={}",
                    collectionName, rootMessage(e));
            return null;
        }
    }

    /**
     * @param fields 既有字段列表
     * @return embedding 维度；字段缺失或维度非法返回 null
     */
    private static Integer embeddingDimensionOf(List<CreateCollectionReq.FieldSchema> fields) {
        for (CreateCollectionReq.FieldSchema field : fields) {
            if (field != null && MemoryVectorSchema.FIELD_EMBEDDING.equals(field.getName())) {
                return field.getDimension();
            }
        }
        return null;
    }

    /**
     * 确保集合已加载；已加载则跳过。SDK 的 timeout 单位是毫秒，误传秒会立刻超时。
     */
    private void ensureLoaded(MilvusClientV2 client, String collectionName) {
        Boolean loaded = client.getLoadState(
                GetLoadStateReq.builder().collectionName(collectionName).build());
        if (Boolean.TRUE.equals(loaded)) {
            return;
        }
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .sync(Boolean.TRUE)
                .timeout(loadTimeoutMs)
                .build());
    }

    /**
     * 按 memory_id 覆盖写入。调用方已归一化向量并校验维度，这里只负责组装行。
     *
     * @param collectionName 记忆 collection
     * @param row            待写入行
     * @throws MemoryVectorStoreException 参数非法、Milvus 不可用或写入失败
     */
    @Override
    public void upsert(String collectionName, MemoryVectorRow row) {
        if (!StringUtils.hasText(collectionName)) {
            throw new MemoryVectorStoreException("记忆 collection 未配置");
        }
        if (row == null || row.memoryId() <= 0) {
            throw new MemoryVectorStoreException("记忆向量行缺少 memory_id");
        }
        float[] vector = row.embedding();
        if (vector == null || vector.length == 0) {
            throw new MemoryVectorStoreException("记忆向量为空，无法写入");
        }
        MilvusClientV2 client = client();
        try {
            client.upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(toJson(row, vector)))
                    .build());
            log.debug("[MEMORY] 向量已写入 collection={} memoryId={} dim={}",
                    collectionName, row.memoryId(), vector.length);
        } catch (Exception e) {
            throw new MemoryVectorStoreException("记忆向量写入失败：" + rootMessage(e), e);
        }
    }

    /**
     * 按主键删除。id 不存在视为成功（幂等）；空集合直接返回，不发起出站。
     *
     * @param collectionName 记忆 collection
     * @param memoryIds      待删除主键
     * @throws MemoryVectorStoreException Milvus 不可用或删除失败
     */
    @Override
    public void remove(String collectionName, Collection<Long> memoryIds) {
        if (!StringUtils.hasText(collectionName)) {
            throw new MemoryVectorStoreException("记忆 collection 未配置");
        }
        List<Object> ids = distinctPositive(memoryIds);
        if (ids.isEmpty()) {
            return;
        }
        MilvusClientV2 client = client();
        try {
            for (int from = 0; from < ids.size(); from += DELETE_BATCH) {
                List<Object> part = ids.subList(from, Math.min(from + DELETE_BATCH, ids.size()));
                client.delete(DeleteReq.builder()
                        .collectionName(collectionName)
                        .ids(new ArrayList<>(part))
                        .build());
            }
            log.debug("[MEMORY] 向量已删除 collection={} ids={}", collectionName, ids.size());
        } catch (Exception e) {
            throw new MemoryVectorStoreException("记忆向量删除失败：" + rootMessage(e), e);
        }
    }

    /**
     * 按用户 + Agent 检索。过滤条件由 {@link MemoryVectorSchema#searchFilter} 生成：
     * {@code user_id + agent_id + deleted == 0 + (expired_time == 0 || expired_time > now)}。
     * <p>
     * 同时回传 {@code content / importance / expired_time}：正文副本供上层在「主键回查拿不到正文」时兜底，
     * 但注入内容仍以 MySQL 对齐结果为准（向量库可能落后于 MySQL）。
     *
     * @param collectionName 记忆 collection
     * @param userId         平台用户；null 按 0 处理
     * @param agentId        Agent；null 按 0 处理
     * @param queryVector    查询向量（已 L2 归一化）
     * @param topK           候选条数上限
     * @return 命中，按分数降序
     * @throws MemoryVectorStoreException 参数非法、Milvus 不可用或检索失败
     */
    @Override
    public List<MemoryVectorHit> search(String collectionName, Long userId, Long agentId,
                                        float[] queryVector, int topK) {
        if (!StringUtils.hasText(collectionName)) {
            throw new MemoryVectorStoreException("记忆 collection 未配置");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new MemoryVectorStoreException("查询向量为空，无法检索");
        }
        long uid = userId == null ? 0L : userId;
        long aid = agentId == null ? 0L : agentId;
        long nowMs = System.currentTimeMillis();
        String filter = MemoryVectorSchema.searchFilter(uid, aid, nowMs);
        MilvusClientV2 client = client();
        try {
            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField(MemoryVectorSchema.FIELD_EMBEDDING)
                    .data(List.of(new FloatVec(toList(queryVector))))
                    .topK(Math.max(1, topK))
                    .metricType(IndexParam.MetricType.IP)
                    .filter(filter)
                    .outputFields(OUTPUT_FIELDS)
                    .build());
            List<MemoryVectorHit> hits = hitsOf(resp);
            // 命 0 时把过滤条件打出来：过期/隔离条件写错会表现为「永远召不回」，没有这行很难定位
            log.debug("[MEMORY] 向量检索 collection={} userId={} agentId={} topK={} hits={} filter={}",
                    collectionName, uid, aid, topK, hits.size(), filter);
            return hits;
        } catch (Exception e) {
            throw new MemoryVectorStoreException("记忆向量检索失败：" + rootMessage(e), e);
        }
    }

    /**
     * 检索响应转命中；按 Milvus 返回顺序（分数降序）保留，并带回正文/重要度/过期时间。
     */
    private static List<MemoryVectorHit> hitsOf(SearchResp resp) {
        List<MemoryVectorHit> out = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null) {
            return out;
        }
        for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
            if (group == null) {
                continue;
            }
            for (SearchResp.SearchResult item : group) {
                if (item == null || item.getId() == null) {
                    continue;
                }
                // 主键固定是 Int64 的 memory_id；非数字主键说明集合被别的业务污染，直接跳过
                long memoryId = asLong(item.getId());
                if (memoryId <= 0) {
                    continue;
                }
                float score = item.getScore() == null ? 0f : item.getScore().floatValue();
                Map<String, Object> entity = item.getEntity();
                out.add(new MemoryVectorHit(memoryId, score,
                        asString(entity, MemoryVectorSchema.FIELD_CONTENT),
                        (int) asLong(entity, MemoryVectorSchema.FIELD_IMPORTANCE),
                        asLong(entity, MemoryVectorSchema.FIELD_EXPIRED_TIME)));
            }
        }
        return out;
    }

    /**
     * 从检索命中里取整数字段；缺失按 0（Int64 标量没有 null 语义）。
     *
     * @param entity 命中实体；可空
     * @param key    字段名
     * @return 数值；缺失/非数字为 0
     */
    private static long asLong(Map<String, Object> entity, String key) {
        Object value = entity == null ? null : entity.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 从检索命中里取正文；缺失返回 null（上层据此判断是否可用向量侧正文兜底）。
     *
     * @param entity 命中实体；可空
     * @param key    字段名
     * @return 正文；缺失为 null
     */
    private static String asString(Map<String, Object> entity, String key) {
        Object value = entity == null ? null : entity.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 主键转 long；无法解析返回 -1。
     */
    private static long asLong(Object id) {
        if (id instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(id).trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    /**
     * 组装一条 Milvus 行。标量空值按 0 / 空串落库（Int64、VarChar 都不接受 null）。
     * <p>
     * {@code expired_time} 用哨兵 0 表示无过期，与 {@link MemoryVectorSchema#searchFilter} 成对；
     * {@code content} 已由 {@link MemoryVectorRow} 按 {@link MemoryVectorSchema#CONTENT_MAX_LENGTH} 截断。
     */
    private static JsonObject toJson(MemoryVectorRow row, float[] vector) {
        JsonObject json = new JsonObject();
        json.addProperty(MemoryVectorSchema.FIELD_MEMORY_ID, row.memoryId());
        json.addProperty(MemoryVectorSchema.FIELD_USER_ID, row.userId() == null ? 0L : row.userId());
        json.addProperty(MemoryVectorSchema.FIELD_AGENT_ID, row.agentId() == null ? 0L : row.agentId());
        json.addProperty(MemoryVectorSchema.FIELD_FACT_KEY, cut(row.factKey(), MemoryVectorSchema.FACT_KEY_MAX_LENGTH));
        // 有效标记恒写 0：物理删除走 remove，向量库里不保留半失效行
        json.addProperty(MemoryVectorSchema.FIELD_DELETED, 0);
        json.addProperty(MemoryVectorSchema.FIELD_EXPIRED_TIME, row.expiredTime());
        json.addProperty(MemoryVectorSchema.FIELD_IMPORTANCE, row.importance());
        json.addProperty(MemoryVectorSchema.FIELD_CONTENT,
                cut(row.content(), MemoryVectorSchema.CONTENT_MAX_LENGTH));
        json.add(MemoryVectorSchema.FIELD_EMBEDDING, toJsonArray(vector));
        return json;
    }

    private static JsonArray toJsonArray(float[] vector) {
        JsonArray array = new JsonArray();
        for (float value : vector) {
            array.add(value);
        }
        return array;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 去重并剔除非法主键，避免把 null / 0 当主键发给 Milvus。
     */
    private static List<Object> distinctPositive(Collection<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : memoryIds) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 取连接；holder 的 IllegalStateException 转成本域异常，上层只处理一种失败。
     */
    private MilvusClientV2 client() {
        try {
            return holder.client();
        } catch (IllegalStateException e) {
            throw new MemoryVectorStoreException("记忆向量库连接失败", e);
        }
    }

    /**
     * 取最内层异常消息，避免把 gRPC 长堆栈写进日志/原因。
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
