package com.agenthub.memory.domain.service;

import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 记忆专用 collection 的字段模板与检索表达式。所属域：memory。
 * <p>
 * 字段口径（本刀定稿，后续写/召回必须复用本类常量）：
 * <ul>
 *   <li>{@code memory_id}：主键，取 MySQL {@code memory_item.id}，upsert 天然幂等（双写不产生重复行）；</li>
 *   <li>{@code embedding}：dense 向量，维度来自平台级 Embedding 实例的连通测试快照
 *       {@code model_instance.embedding_dim}（YAML {@code agenthub.memory.embedding-dimension} 仅排障对照，不参与建集）；</li>
 *   <li>{@code user_id} / {@code agent_id}：召回隔离维度，查询必须带上；</li>
 *   <li>{@code fact_key}：LONG 稳定事实键，便于排障与同键更新；</li>
 *   <li>{@code deleted}：有效标记（0 有效 / 1 失效），召回强制排除 1；</li>
 *   <li>{@code expired_time}：过期时间（<b>毫秒时间戳</b>），<b>无过期写哨兵 0</b>；
 *       命名统一用 {@code _time} 后缀，禁止 {@code *_at} / {@code expire_at}；</li>
 *   <li>{@code importance}：与 MySQL {@code memory_item.importance} 同语义（0~100），供召回侧按重要度重排/截断；</li>
 *   <li>{@code content}：正文副本，超 {@link #CONTENT_MAX_LENGTH} 截断。
 *       存它不是为了替代 MySQL（MySQL 仍是真源），而是让「只靠 memory_id 才能拿到正文」不再是唯一路径。</li>
 * </ul>
 * 维度只允许一个来源：平台级 Embedding 的 {@code embedding_dim} 快照（由调用方透传）。
 * 本类不提供默认维度，也不读 YAML 的 {@code embedding-dimension}（那只用于排障对照），
 * 避免出现第二套常量后两边漂移、写入永久失败。
 * <p>
 * <b>已建集合不会自动迁移</b>：Milvus 不支持原地加字段。缺字段时 {@code ensureCollection} 会抛错让
 * {@code isReady=false}（召回回落 MySQL），由人工按 {@code docs/记忆架构/LONG-Milvus-schema-v2-字段与重建-2026-11-07.md}
 * 重建；代码<b>绝不静默 drop 生产数据</b>。
 */
public final class MemoryVectorSchema {

    /** 主键字段名；值取 memory_item.id */
    public static final String FIELD_MEMORY_ID = "memory_id";
    /** dense 向量字段名 */
    public static final String FIELD_EMBEDDING = "embedding";
    /** 平台用户维度，召回必须带 */
    public static final String FIELD_USER_ID = "user_id";
    /** Agent 维度，召回必须带 */
    public static final String FIELD_AGENT_ID = "agent_id";
    /** LONG 稳定事实键；与 memory_item.fact_key 同宽（varchar(64)） */
    public static final String FIELD_FACT_KEY = "fact_key";
    /** 有效标记：0 有效、1 失效；用 Int8 而非 Bool，与 MySQL tinyint 语义一致 */
    public static final String FIELD_DELETED = "deleted";
    /** 过期时间：毫秒时间戳；无过期写哨兵 {@link #NO_EXPIRY_MILLIS} */
    public static final String FIELD_EXPIRED_TIME = "expired_time";
    /** 重要程度：0~100，与 memory_item.importance 同语义 */
    public static final String FIELD_IMPORTANCE = "importance";
    /** 正文副本（截断后的），供召回侧直接拼装；MySQL 仍是真源 */
    public static final String FIELD_CONTENT = "content";

    /** fact_key 最大长度，对齐 memory_item.fact_key varchar(64) */
    public static final int FACT_KEY_MAX_LENGTH = 64;
    /**
     * 正文在向量侧的最大长度。取 2000 与双写送 Embedding 的截断口径一致：
     * 一段记忆最终注入提示词时受 {@code recall-max-chars} 总预算约束，存全量正文只增行宽、不增可用信息。
     */
    public static final int CONTENT_MAX_LENGTH = 2000;
    /**
     * 无过期的哨兵值。用 0 而不是 null：Milvus 的 Int64 标量不接受 null，
     * 且过滤表达式可以写成「等于 0 或大于当前时刻」，不需要额外的空值判断函数。
     */
    public static final long NO_EXPIRY_MILLIS = 0L;

    /**
     * 时间换算用的时区。与 Spring/Jackson（{@code Asia/Shanghai}）和 JDBC {@code serverTimezone}
     * 保持一致：写入与过滤必须用同一换算，否则边界会差 8 小时。
     */
    private static final ZoneId EPOCH_ZONE = ZoneId.of("Asia/Shanghai");

    /** dense 索引名，便于 describe 时定位 */
    public static final String VECTOR_INDEX = "idx_memory_embedding";

    /** 集合必须具备的标量/正文/向量字段（不含主键，主键单独算） */
    private static final List<String> REQUIRED_FIELDS = List.of(
            FIELD_EMBEDDING, FIELD_USER_ID, FIELD_AGENT_ID, FIELD_FACT_KEY,
            FIELD_DELETED, FIELD_EXPIRED_TIME, FIELD_IMPORTANCE, FIELD_CONTENT);

    private MemoryVectorSchema() {
    }

    /**
     * 组装建集字段列表（顺序固定，便于跨环境对齐）。
     *
     * @param dimension 向量维度，必须 &gt; 0；由调用方透传平台级 Embedding 的 {@code embedding_dim} 快照
     * @return 新列表，调用方可安全交给 SDK
     */
    public static List<CreateCollectionReq.FieldSchema> fields(int dimension) {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        fields.add(primaryKeyField());
        fields.add(vectorField(dimension));
        fields.add(int64(FIELD_USER_ID));
        fields.add(int64(FIELD_AGENT_ID));
        fields.add(varchar(FIELD_FACT_KEY, FACT_KEY_MAX_LENGTH));
        fields.add(int8(FIELD_DELETED));
        fields.add(int64(FIELD_EXPIRED_TIME));
        fields.add(int64(FIELD_IMPORTANCE));
        fields.add(varchar(FIELD_CONTENT, CONTENT_MAX_LENGTH));
        return fields;
    }

    /**
     * 主键：Int64、非自增。双写时直接 upsert 同一个 memory_id，重复执行不产生重复行。
     *
     * @return 主键字段
     */
    public static CreateCollectionReq.FieldSchema primaryKeyField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name(FIELD_MEMORY_ID)
                .dataType(DataType.Int64)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .build();
    }

    /**
     * dense 向量字段。维度由调用方从平台级 Embedding 的 {@code embedding_dim} 快照透传；
     * YAML {@code embedding-dimension} 仅排障对照，本方法不读、也不兜底任何默认值。
     *
     * @param dimension 向量维度，必须 &gt; 0
     * @return 向量字段
     */
    public static CreateCollectionReq.FieldSchema vectorField(int dimension) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(FIELD_EMBEDDING)
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build();
    }

    /**
     * 向量索引：AUTOINDEX + IP。与 RAG 一致，要求写库前对向量做 L2 归一化（刀2 双写负责）。
     *
     * @return 索引参数
     */
    public static IndexParam vectorIndex() {
        return IndexParam.builder()
                .fieldName(FIELD_EMBEDDING)
                .indexName(VECTOR_INDEX)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.IP)
                .build();
    }

    /**
     * MySQL 过期时间 → Milvus Int64 毫秒；null 转哨兵 0。
     * <p>
     * 负值同样归到 0：MySQL 侧不允许负的过期时间，出现即上游脏数据，
     * 按「无过期」处理后由定时清理与召回对齐两处纠正，比写入一个永远不该命中的过去时间更安全。
     *
     * @param expiredTime MySQL 的 {@code expired_time}，可空
     * @return 毫秒时间戳；无过期/非法为 {@link #NO_EXPIRY_MILLIS}
     */
    public static long toEpochMillis(LocalDateTime expiredTime) {
        if (expiredTime == null) {
            return NO_EXPIRY_MILLIS;
        }
        long millis = expiredTime.atZone(EPOCH_ZONE).toInstant().toEpochMilli();
        return Math.max(millis, NO_EXPIRY_MILLIS);
    }

    /**
     * 正文截断：null 转空串（VarChar 不接受 null），超过 {@link #CONTENT_MAX_LENGTH} 截断。
     *
     * @param content 原始正文
     * @return 可写入 Milvus 的正文副本
     */
    public static String clipContent(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= CONTENT_MAX_LENGTH ? content : content.substring(0, CONTENT_MAX_LENGTH);
    }

    /**
     * 召回过滤表达式：{@code user_id + agent_id + deleted==0 + 未过期}。
     * <p>
     * 过期判定写死为「哨兵 0 或 大于当前毫秒」，与 {@link #toEpochMillis(LocalDateTime)} 的写入口径成对：
     * 缺一半就会出现「写入带过期、检索不过滤」的漏洞。放进本类是为了能脱离 Milvus 单测。
     *
     * @param userId  平台用户；null 按 0（与写入口径一致）
     * @param agentId Agent；null 按 0
     * @param nowMs   当前毫秒时间戳
     * @return Milvus 布尔表达式
     */
    public static String searchFilter(long userId, long agentId, long nowMs) {
        return FIELD_USER_ID + " == " + userId
                + " && " + FIELD_AGENT_ID + " == " + agentId
                + " && " + FIELD_DELETED + " == 0"
                + " && (" + FIELD_EXPIRED_TIME + " == " + NO_EXPIRY_MILLIS
                + " || " + FIELD_EXPIRED_TIME + " > " + nowMs + ")";
    }

    /**
     * 比对既有集合的字段名与本刀要求的字段，返回缺失项。
     * <p>
     * 用于 {@code ensureCollection} 的演进检测：Milvus 不能原地加字段，缺字段时只能人工重建。
     *
     * @param present 既有集合的字段名集合；null/空视为「读不到」由调用方决定策略
     * @return 缺失字段名；空列表表示齐全
     */
    public static List<String> missingFields(Collection<String> present) {
        if (present == null || present.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_FIELDS) {
            if (!present.contains(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    /**
     * 本刀要求的字段全集（不含主键），供运维比对与文档使用。
     *
     * @return 不可变字段名列表
     */
    public static List<String> requiredFieldNames() {
        return new LinkedHashSet<>(REQUIRED_FIELDS).stream().toList();
    }

    private static CreateCollectionReq.FieldSchema varchar(String name, int maxLength) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .build();
    }

    private static CreateCollectionReq.FieldSchema int64(String name) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.Int64)
                .build();
    }

    private static CreateCollectionReq.FieldSchema int8(String name) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.Int8)
                .build();
    }
}
