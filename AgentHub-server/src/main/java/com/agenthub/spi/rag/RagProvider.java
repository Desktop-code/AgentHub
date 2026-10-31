package com.agenthub.spi.rag;

import com.agenthub.rag.domain.model.RagKnowledgeBase;

import java.util.List;
import java.util.Map;

/**
 * 知识库存储扩展点。所属域：spi。
 * 调用方：rag 的应用服务（建库/删库/入库/检索）与 RAG 检索 Tool。
 * 实现方：一期只有 {@code LocalMilvusRagProvider}；API 型 Provider 未注册。
 * <p>
 * 线程相关：实现必须是线程安全的。创建/删除走管理端低频请求；入库在受限线程池内执行；检索会被多会话并发调用。
 * 约束：本接口只接受领域对象与值对象，不把 Collection 名暴露给 rag 域之外的业务代码。
 */
public interface RagProvider {

    /**
     * 建 Store：按 KB 的维度与平台固定字段模板建 Collection，并写入 KB 对象上的 collection_name / capability。
     * <p>
     * 实现应做到：集合已存在时视为成功（幂等）；失败抛出 {@link RagStoreException}，由应用服务把 KB 标记为 INIT_FAILED。
     *
     * @param kb 已落库、带 id / 维度 / 隔离模式的知识库
     * @throws RagStoreException Milvus 不可用、维度非法、建集被拒
     */
    void createStore(RagKnowledgeBase kb);

    /**
     * 删 Store：删除 KB 对应的 Collection。
     * <p>
     * 集合不存在时视为成功（幂等）。实现不得删除其它 KB 的集合。
     *
     * @param kb 已落库的知识库
     * @throws RagStoreException Milvus 不可用
     */
    void dropStore(RagKnowledgeBase kb);

    /**
     * 写入（upsert）一批切片向量。按主键幂等：重复调用同一批覆盖而非追加。
     * <p>
     * 实现负责：把 content 截断到集合字段上限、把标量字段按模板落列、
     * **不支持 BM25 时忽略稀疏字段，绝不因为缺 sparse 而失败**。
     *
     * @param kb    知识库，用于取集合名与能力
     * @param rows  待写切片，至少一条
     * @throws RagStoreException Milvus 不可用或写入被拒
     */
    void upsertChunks(RagKnowledgeBase kb, List<ChunkVector> rows);

    /**
     * 按文档删除向量。文档级联删除与重灌前清理都走这里。
     * <p>
     * 实现应做到：无命中视为成功（幂等）；分批删除避免单次 id 过多。
     *
     * @param kb    知识库
     * @param docId 文档 id
     * @throws RagStoreException Milvus 不可用
     */
    void deleteByDocId(RagKnowledgeBase kb, Long docId);

    /**
     * 向量检索。只用稠密向量，不涉及 BM25/融合；融合由上层编排。
     *
     * @param kb      知识库
     * @param query   检索句
     * @param topK    召回条数
     * @param vectors 已算好的查询向量；由调用方保证维度与 KB 一致
     * @return 命中，按相似度降序；无命中返回空列表
     * @throws RagStoreException Milvus 不可用
     */
    List<SearchResult> searchVector(RagKnowledgeBase kb, String query, int topK, float[] vectors);

    /**
     * BM25 全文检索。集合能力为 DENSE_ONLY 时应返回空列表，不得抛错——
     * 上层据此退化为纯向量，而不是让整次问答失败。
     *
     * @param kb    知识库
     * @param query 检索句
     * @param topK  召回条数
     * @return 命中；能力不支持或无命中均返回空列表
     */
    List<SearchResult> searchBm25(RagKnowledgeBase kb, String query, int topK);

    /**
     * 综合检索：向量必做；BM25 与 RRF 仅当能力支持。Rerank / 改写不在此层（由上层决定是否调用模型）。
     * <p>
     * 默认实现只做向量，便于测试替身与 API 型 Provider 复用。
     *
     * @param kb    知识库
     * @param sq    已解析的检索参数
     * @param query 查询向量
     * @return 融合后命中，长度 ≤ sq.topK；无命中返回空列表
     */
    default List<SearchResult> retrieve(RagKnowledgeBase kb, SearchQuery sq, float[] query) {
        return searchVector(kb, sq.getQuery(), Math.max(1, sq.getTopK()), query);
    }

    /**
     * 探测集合能力，用于重建后回填与健康检查。
     *
     * @param kb 知识库
     * @return DENSE_BM25 / DENSE_ONLY
     */
    default String probeCapability(RagKnowledgeBase kb) {
        return kb.getStoreCapability() == null ? "DENSE_ONLY" : kb.getStoreCapability();
    }

    /**
     * 能力探测的原始结果，供上层判断能否走 BM25 分支。
     *
     * @param kb 知识库
     * @return true 表示集合具备 sparse + BM25
     */
    default boolean supportsBm25(RagKnowledgeBase kb) {
        return "DENSE_BM25".equals(probeCapability(kb));
    }

    /**
     * 集合统计，供运维排查「库里有多少切片」。缺省不支持时返回空 Map。
     *
     * @param kb 知识库
     * @return 统计项，可空
     */
    default Map<String, Object> stats(RagKnowledgeBase kb) {
        return Map.of();
    }
}
