package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.service.MemoryVectorMath;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.spi.rag.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 记忆向量的「查询侧」出站。所属域：memory 的 application。
 * <p>
 * 只做一件事：query 文本 → Embedding(平台级) → 记忆 collection 按 user+agent 检索 → 主键+分数。
 * 不做 MySQL 对齐、不做 rerank、不做条数截断（都在 {@link MemoryLongRecall}）。
 * <p>
 * 硬约束（每一条都对应一条验收口径）：
 * <ul>
 *   <li><b>每轮每类出站 ≤1</b>：默认 attempts=1，配置最多 2（即最多一次短重试）；</li>
 *   <li><b>超时必降级</b>：出站经 {@link MemoryRecallGateway} 加到 {@code recall-vector-timeout-ms} 为止，
 *       超时后本轮按失败处理，调用方回落 MySQL；</li>
 *   <li><b>任何失败都不抛</b>：未就绪、被拒绝、超时、维度不符、Milvus 异常一律返回空列表；</li>
 *   <li>空 query 不调 Embedding（省一次出站）；</li>
 *   <li>查询向量与写入同口径做 L2 归一化，否则 IP 分数不可比。</li>
 * </ul>
 * 本类不写库、不改数据，只读。
 */
@Component
public class MemoryVectorRecall {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorRecall.class);

    /** 送入 Embedding 的 query 上限：超长 query 会把相似度算糊，也浪费出站配额 */
    static final int MAX_QUERY_CHARS = 500;

    /** 单轮尝试次数硬顶：规格要求「重试 ≤1」，因此最多两次出站 */
    private static final int MAX_ATTEMPTS_CAP = 2;

    private final MemoryVectorSupport support;
    private final EmbeddingClient embeddings;
    private final MemoryVectorStore store;
    private final MemoryRecallGateway gateway;
    private final MemoryProperties properties;

    /**
     * @param support    就绪快照（collection / 维度快照 / 平台级 Embedding / 平台级 Rerank）
     * @param embeddings 向量化出站
     * @param store      记忆向量存储端口
     * @param gateway    带超时的召回执行器
     * @param properties 候选条数、超时、尝试次数
     */
    public MemoryVectorRecall(MemoryVectorSupport support, EmbeddingClient embeddings, MemoryVectorStore store,
                             MemoryRecallGateway gateway, MemoryProperties properties) {
        this.support = support;
        this.embeddings = embeddings;
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
    }

    /**
     * 取就绪快照；探测异常按未就绪处理，绝不把异常抛给主对话链路。
     *
     * @return 快照；异常时为 null
     */
    public MemoryVectorReadiness readiness() {
        try {
            return support == null ? null : support.readiness();
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 召回就绪探测异常，按未就绪处理 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 向量候选检索。任何失败都返回空列表，由调用方回落 MySQL Top N。
     *
     * @param agentId Agent，可空（按 0 处理）
     * @param userId  平台用户，可空（按 0 处理）
     * @param query   查询文本；空则直接返回空列表且不出站
     * @param topK    候选条数上限，&gt;0
     * @return 命中按分数降序；失败/无命中为空列表
     */
    public List<MemoryVectorHit> candidates(Long agentId, Long userId, String query, int topK) {
        String text = query == null ? "" : query.trim();
        // 空 query 不调 Embedding：没有语义可供检索，直接让上层走 MySQL
        if (text.isEmpty()) {
            return List.of();
        }
        MemoryVectorReadiness snapshot = readiness();
        if (snapshot == null || !snapshot.ready()) {
            log.debug("[MEMORY] 向量召回跳过 reason=not_ready agentId={} userId={}", agentId, userId);
            return List.of();
        }
        int dimension = snapshot.dimension();
        String clipped = text.length() <= MAX_QUERY_CHARS ? text : text.substring(0, MAX_QUERY_CHARS);

        float[] vector = withRetry("embed", () ->
                embeddings.embedOne(snapshot.embeddingInstanceId(), clipped, dimension));
        if (vector == null) {
            return List.of();
        }
        // 维度不符的查询向量算出来的分数没有意义：宁可回落 MySQL，也不能注入噪声
        if (vector.length != dimension) {
            log.warn("[MEMORY] 向量召回跳过 reason=query_dimension_mismatch expected={} actual={}",
                    dimension, vector.length);
            return List.of();
        }
        float[] normalized = MemoryVectorMath.l2Normalize(vector);
        List<MemoryVectorHit> hits = withRetry("search", () ->
                store.search(snapshot.collectionName(), userId, agentId, normalized, Math.max(topK, 1)));
        if (hits == null || hits.isEmpty()) {
            log.debug("[MEMORY] 向量召回无命中 agentId={} userId={} topK={}", agentId, userId, topK);
            return List.of();
        }
        log.debug("[MEMORY] 向量召回命中 agentId={} userId={} topK={} hits={}",
                agentId, userId, topK, hits.size());
        return hits;
    }

    /**
     * 最多尝试 {@code recall-vector-max-attempts} 次（硬顶 2）；全部失败返回 null。
     *
     * @param action 日志用动作名
     * @param call   出站动作
     * @param <T>    返回类型
     * @return 结果；失败为 null
     */
    private <T> T withRetry(String action, Supplier<T> call) {
        int attempts = Math.min(Math.max(properties.getRecallVectorMaxAttempts(), 1), MAX_ATTEMPTS_CAP);
        long timeoutMs = Math.max(properties.getRecallVectorTimeoutMs(), 0L);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            T result = gateway.call(action, timeoutMs, call);
            if (result != null) {
                return result;
            }
            if (attempt < attempts) {
                log.debug("[MEMORY] 向量召回失败，重试一次 action={} attempt={}", action, attempt);
            }
        }
        return null;
    }
}
