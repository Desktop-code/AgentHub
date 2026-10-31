package com.agenthub.rag.domain.service;

import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

import java.util.ArrayList;
import java.util.List;

/**
 * Collection 标量字段模板。所属域：rag。平台定死，管理员不配字段（见架构定稿 §10.3）。
 * <p>
 * 模板分两层：
 * <ul>
 *   <li>{@link #scalarFields()} —— 每个 KB 都一样的标量字段，顺序固定，便于跨库对齐；</li>
 *   <li>{@link #vectorField(int)} / {@link #contentField(int)} —— 随维度或配置变化的部分。</li>
 * </ul>
 * 约束：改本类等于改所有新库的 schema，必须同步 ② 入库写入逻辑与 docs 字段表；
 * 已建好的库不会自动迁移（Milvus 加字段代价高），需要重建集合。
 */
public final class RagCollectionSchema {

    /** 主键字段名；值取 MySQL rag_chunk.id，删改双向可查 */
    public static final String FIELD_ID = "id";
    /** dense 向量字段名 */
    public static final String FIELD_EMBEDDING = "embedding";
    /** BM25 全文文本字段名；同时是 sparse 的输入 */
    public static final String FIELD_CONTENT = "content";
    /** Milvus 内置 BM25 生成的稀疏向量字段名 */
    public static final String FIELD_SPARSE = "sparse";

    /** BM25 全文文本上限。BGE 512 token 量级，8192 字符足以覆盖单个切片 */
    public static final int CONTENT_MAX_LENGTH = 8192;

    private RagCollectionSchema() {
    }

    /**
     * 与 KB 无关的标量字段（顺序即建集顺序）。
     * 字段口径严格对齐 docs/架构定稿-企业级知识库RAG.md §10.3，多一个都不加。
     *
     * @return 不可变字段列表
     */
    public static List<CreateCollectionReq.FieldSchema> scalarFields() {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        // kb_id 必有：PHYSICAL 下一库一集本可省略，但 LOGICAL 二期限共集，先留成一等字段
        fields.add(int64("kb_id"));
        fields.add(int64("doc_id"));
        fields.add(int64("chunk_id"));
        fields.add(int64("tenant_id"));
        fields.add(int64("dept_id"));
        fields.add(int64("version"));
        fields.add(varchar("content_hash", 64));
        // deleted 用 Int8 而非 Bool：Milvus 标量过滤对整数更稳，且与 MySQL tinyint 语义一致
        fields.add(int8("deleted"));
        fields.add(int64("effective_from"));
        fields.add(int64("expire_time"));
        fields.add(int64("create_by"));
        fields.add(int64("owner_user_id"));
        // 展示用，检索命中后可直接回给 Agent，不必再回查 MySQL
        fields.add(varchar("title", 255));
        fields.add(varchar("source", 255));
        return List.copyOf(fields);
    }

    /**
     * 主键：Int64、非自增。值由平台在写 MySQL 后回填，保证两边可对齐。
     *
     * @return 主键字段
     */
    public static CreateCollectionReq.FieldSchema idField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name(FIELD_ID)
                .dataType(DataType.Int64)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .build();
    }

    /**
     * dense 向量字段。
     *
     * @param dimension 建库时锁定的维度，必须 > 0
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
     * BM25 输入文本字段：开 chinese analyzer，供 Milvus 服务端分词后生成 sparse。
     *
     * @return 文本字段
     */
    public static CreateCollectionReq.FieldSchema analyzedContentField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name(FIELD_CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(CONTENT_MAX_LENGTH)
                .enableAnalyzer(Boolean.TRUE)
                .analyzerParams(java.util.Map.of("type", "chinese"))
                .build();
    }

    /**
     * 纯向量降级时使用的文本字段：无 analyzer，只作展示与回灌。
     *
     * @return 文本字段
     */
    public static CreateCollectionReq.FieldSchema plainContentField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name(FIELD_CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(CONTENT_MAX_LENGTH)
                .build();
    }

    /**
     * BM25 稀疏向量字段。
     *
     * @return sparse 字段
     */
    public static CreateCollectionReq.FieldSchema sparseField() {
        return CreateCollectionReq.FieldSchema.builder()
                .name(FIELD_SPARSE)
                .dataType(DataType.SparseFloatVector)
                .build();
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
