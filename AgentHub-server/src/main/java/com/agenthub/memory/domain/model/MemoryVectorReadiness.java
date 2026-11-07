package com.agenthub.memory.domain.model;

/**
 * 记忆向量库就绪快照。所属域：memory。
 * <p>
 * {@code ready=true} 的口径（全部满足）：Milvus 可用、记忆专用 collection ensure 成功、
 * 模型中心存在「平台锁定」的 Embedding 且已启用、该实例有最近一次连通测试成功快照的维度。
 * Rerank 平台实例只体现在 {@link #rerankInstanceId}：为空表示召回跳过后重排，但绝不影响 ready。
 * <p>
 * {@code collectionDimension} 是现网集合的真实维度（读不到为 null），用于把「维度冲突」变成
 * 可观测状态：{@link #dimensionConflict()} 为真时，前端应引导操作者走受控重建，
 * 而不是只留一行后台 WARN。
 *
 * @param ready               是否可用于 LONG 向量双写/召回
 * @param collectionName      YAML 配置的记忆专用 collection
 * @param dimension           平台级 Embedding 的维度快照（建集与校验共用；未就绪可为 0）
 * @param embeddingInstanceId 实际使用的 Embedding 实例 id；未就绪时为 null
 * @param rerankInstanceId    解析到的 Rerank 实例 id；null 表示不可用
 * @param reason              未就绪原因（可读中文）；就绪时为空串
 * @param collectionDimension 现网集合的真实维度；集合不存在或读不到为 null
 */
public record MemoryVectorReadiness(boolean ready, String collectionName, int dimension,
                                    Long embeddingInstanceId, Long rerankInstanceId, String reason,
                                    Integer collectionDimension) {

    /**
     * @return 是否可做重排；只反映平台级 Rerank 的可用性
     */
    public boolean rerankReady() {
        return rerankInstanceId != null;
    }

    /**
     * 是否存在「集合维度 ≠ 平台级 Embedding 快照维度」的冲突。
     * <p>
     * 判定只看事实（两个维度都存在且不等），不看 ready：冲突时 ready 必然为 false，
     * 但 ready=false 也可能由 Milvus 不可用等原因造成，不能反推。
     *
     * @return true 表示需要走受控重建
     */
    public boolean dimensionConflict() {
        return collectionDimension != null && collectionDimension > 0
                && dimension > 0 && collectionDimension != dimension;
    }

    /**
     * 构造未就绪快照。
     *
     * @param collectionName 配置的 collection（可为空串）
     * @param dimension      配置的维度（可为非正数）
     * @param reason         未就绪原因
     * @return 未就绪快照
     */
    public static MemoryVectorReadiness notReady(String collectionName, int dimension, String reason) {
        return notReady(collectionName, dimension, reason, null);
    }

    /**
     * 构造未就绪快照，并带上现网集合维度（用于区分「维度冲突」与其它未就绪原因）。
     *
     * @param collectionName      配置的 collection（可为空串）
     * @param dimension           快照维度（可为非正数）
     * @param reason              未就绪原因
     * @param collectionDimension 现网集合维度，读不到为 null
     * @return 未就绪快照
     */
    public static MemoryVectorReadiness notReady(String collectionName, int dimension, String reason,
                                                 Integer collectionDimension) {
        return new MemoryVectorReadiness(false, collectionName == null ? "" : collectionName, dimension,
                null, null, reason == null ? "" : reason, collectionDimension);
    }

    /**
     * 构造就绪快照。就绪时集合维度必然等于快照维度。
     *
     * @param collectionName      已确保存在的 collection
     * @param dimension           建集/校验使用的维度
     * @param embeddingInstanceId 已校验的 Embedding 实例 id
     * @param rerankInstanceId    Rerank 实例 id，可空
     * @return 就绪快照
     */
    public static MemoryVectorReadiness ready(String collectionName, int dimension,
                                              long embeddingInstanceId, Long rerankInstanceId) {
        return new MemoryVectorReadiness(true, collectionName, dimension,
                embeddingInstanceId, rerankInstanceId, "", dimension);
    }
}
