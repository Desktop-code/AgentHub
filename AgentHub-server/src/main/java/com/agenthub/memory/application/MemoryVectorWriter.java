package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.model.MemoryVectorRow;
import com.agenthub.memory.domain.service.MemoryVectorMath;
import com.agenthub.memory.domain.service.MemoryVectorSchema;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.spi.rag.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LONG 记忆向量的实际写入/删除。所属域：memory 的 application。
 * <p>
 * 职责边界（刻意收窄，避免与 Merger / 召回耦合）：
 * <ul>
 *   <li>只做「一条已落 MySQL 的 LONG → 向量 upsert」与「若干 memory_id → 向量删除」；</li>
 *   <li>不做去重、不改 Merger 的合并口径、不回写 MySQL 正文；</li>
 *   <li>不决定何时触发（双写由 {@link MemoryVectorHook} 提交后投递，存量由 {@link MemoryVectorReconciler} 手动触发）。</li>
 * </ul>
 * 入口只三个：
 * <ul>
 *   <li>{@link #attempt(MemoryItem)}：主路径。返回 {@link Attempt}，调用方据此落状态位；</li>
 *   <li>{@link #upsert(MemoryItem)}：兼容旧调用，等价于忽略 {@code attempt} 的结果；</li>
 *   <li>{@link #remove(Collection)}：删除向量，失败只日志。</li>
 * </ul>
 * 边界与降级：
 * <ul>
 *   <li>空正文不调 Embedding（不浪费出站配额）；</li>
 *   <li>isReady=false（Milvus 不可用 / 无平台级 Embedding / 无维度快照）返回 {@link SyncOutcome#DEFERRED}：
 *       调用方<b>不改状态位</b>，行保持 PENDING 等下一次补同步；</li>
 *   <li>失败只重试有限次（{@code agenthub.memory.vector-write-max-attempts}，默认 3），
 *       耗尽返回 {@link SyncOutcome#FAILED} 与短原因，<b>绝不回滚 MySQL</b>，也不抛回调用方；</li>
 *   <li>Embedding 实例只用模型中心「平台锁定」的那一条，维度只用其连通测试快照 {@code embedding_dim}。</li>
 * </ul>
 * 出站日志不含正文、不含 Key：只打 memoryId / 实例 id / 维度 / 次数。
 */
@Component
public class MemoryVectorWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorWriter.class);

    /** 送入 Embedding 的正文上限：LONG 正文已限制在 8000 字内，向量化只取前段，避免超模型上下文 */
    static final int MAX_EMBED_CHARS = 2000;

    /** 失败原因落库前的截断长度，对齐 memory_item.vector_sync_error varchar(200) */
    static final int MAX_REASON_CHARS = 180;

    private final MemoryVectorSupport support;
    private final EmbeddingClient embeddings;
    private final MemoryVectorStore store;
    private final MemoryProperties properties;

    /**
     * @param support     就绪快照（collection / 维度快照 / 平台级 Embedding）
     * @param embeddings  向量化出站端口
     * @param store       记忆向量存储端口
     * @param properties  重试次数与退避
     */
    public MemoryVectorWriter(MemoryVectorSupport support, EmbeddingClient embeddings,
                              MemoryVectorStore store, MemoryProperties properties) {
        this.support = support;
        this.embeddings = embeddings;
        this.store = store;
        this.properties = properties;
    }

    /**
     * 写入一条 LONG 向量，失败只日志、不抛。等价于忽略 {@link #attempt(MemoryItem)} 的结果。
     *
     * @param item 已落库的 LONG 条目；id/正文缺失时直接跳过
     */
    public void upsert(MemoryItem item) {
        Attempt attempt = attempt(item);
        if (attempt.outcome() == SyncOutcome.SYNCED) {
            log.debug("[MEMORY] 向量写入成功 memoryId={}", item == null ? null : item.getId());
        }
    }

    /**
     * 尽力写入一次并带回结论。双写挂载点与存量补同步都用它，区别只在拿到结论后怎么落状态位。
     * <p>
     * 明确不做：不写 MySQL、不改状态位、不抛异常。状态位是调用方的责任，
     * 这样「写向量」与「记同步进度」两件事各自只有一个实现。
     *
     * @param item 已落库的 LONG 条目
     * @return 结论；{@code DEFERRED} 表示基础设施未就绪（调用方应保持 PENDING）
     */
    public Attempt attempt(MemoryItem item) {
        if (item == null || item.getId() == null) {
            return Attempt.failed("缺少 memory_id");
        }
        String content = item.getContent();
        // 边界：空正文不调 Embedding，也不该占用重试次数（调用方本应提前落终态）
        if (content == null || content.isBlank()) {
            return new Attempt(SyncOutcome.FAILED, "正文为空", false);
        }
        MemoryVectorReadiness readiness = safeReadiness();
        if (readiness == null || !readiness.ready()) {
            log.debug("[MEMORY] 向量写入跳过 reason=not_ready memoryId={}", item.getId());
            return Attempt.deferred("记忆向量未就绪");
        }
        return writeWithRetry(readiness, item, content.trim());
    }

    /**
     * 就绪前提下的有限次重试。成功返回 SYNCED；确定性失败（维度不符）不重试；耗尽返回 FAILED。
     *
     * @param readiness 已就绪快照
     * @param item      条目
     * @param text      已 trim 的正文
     * @return 结论
     */
    private Attempt writeWithRetry(MemoryVectorReadiness readiness, MemoryItem item, String text) {
        long memoryId = item.getId();
        String clipped = clip(text);
        int dimension = readiness.dimension();
        int attempts = Math.max(properties.getVectorWriteMaxAttempts(), 1);
        long backoff = Math.max(properties.getVectorWriteRetryBackoffMs(), 0L);
        String reason = "未知失败";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Attempt outcome = writeOnce(readiness, item, clipped, dimension);
            if (outcome.outcome() == SyncOutcome.SYNCED) {
                return outcome;
            }
            reason = outcome.reason();
            // 维度不符是确定性错误：重试只会再错一遍
            if (!outcome.retryable()) {
                break;
            }
            if (attempt < attempts) {
                log.debug("[MEMORY] 向量写入失败，准备重试 memoryId={} attempt={}/{} reason={}",
                        memoryId, attempt, attempts, reason);
                if (!sleepQuietly(backoff)) {
                    break;
                }
            }
        }
        // 耗尽：只日志。上游 MySQL 已提交，向量缺失由状态位 + 补同步兜住
        log.warn("[MEMORY] 向量写入最终失败 memoryId={} attempts={} reason={}", memoryId, attempts, reason);
        return Attempt.failed(reason);
    }

    /**
     * 单次尝试：embed(平台级 Embedding) → L2 归一化 → upsert(记忆 collection)。
     *
     * @param readiness 就绪快照
     * @param item      条目
     * @param text      已截断的正文
     * @param dimension 平台级 Embedding 的维度快照
     * @return 单次结论
     */
    private Attempt writeOnce(MemoryVectorReadiness readiness, MemoryItem item, String text, int dimension) {
        float[] raw;
        try {
            raw = embeddings.embedOne(readiness.embeddingInstanceId(), text, dimension);
        } catch (RuntimeException e) {
            return Attempt.failed(describe(e));
        }
        // 维度必须在写库前拦住：错维向量会让后续检索全是噪声，且 Milvus 会直接拒绝
        if (raw == null || raw.length != dimension) {
            int actual = raw == null ? 0 : raw.length;
            return new Attempt(SyncOutcome.FAILED, "向量维度不符 expected=" + dimension + " actual=" + actual, false);
        }
        float[] normalized = MemoryVectorMath.l2Normalize(raw);
        // 三个新标量全部取自同一条 MySQL 行快照：过期时间/重要度/正文副本与真源一致，
        // 避免「向量侧说没过期、MySQL 说过期」这类双写漂移
        MemoryVectorRow row = new MemoryVectorRow(item.getId(), item.getUserId(), item.getAgentId(),
                item.getFactKey(), normalized,
                MemoryVectorSchema.toEpochMillis(item.getExpiredTime()),
                item.getImportance() == null ? 0 : item.getImportance(),
                item.getContent());
        try {
            store.upsert(readiness.collectionName(), row);
            return Attempt.synced();
        } catch (RuntimeException e) {
            return Attempt.failed(describe(e));
        }
    }

    /**
     * 删除向量。用于逻辑删除、超限淘汰、过期清理的联动删除；失败只日志。
     * <p>
     * 兼容入口：内部等价于 {@link #removeAttempt(Collection)} 然后忽略结论。
     * 需要按结论落状态位（刀8 过期联删）的调用方应直接用 {@code removeAttempt}。
     *
     * @param memoryIds 待删除的 memory_item.id；空集合直接返回
     */
    public void remove(Collection<Long> memoryIds) {
        removeAttempt(memoryIds);
    }

    /**
     * 尽力删除一次并带回结论。刀8 的过期/淘汰/手删联删都用它：
     * 成功（含点本就不存在）→ 调用方落 SYNCED 终态；未就绪 / 失败 → 分别落 PENDING / FAILED，由补同步收口。
     * <p>
     * 明确不做：不写 MySQL 业务列、不改状态位、不抛异常（状态位是调用方的责任）。
     * <p>
     * 限流：主键按 {@code agenthub.memory.vector-delete-batch-limit}（默认 500）拆批串行发送，
     * 严禁把一次过期清理的全部主键塞进一个请求（几万个 id 会直接打爆 Milvus / 网关）。
     *
     * @param memoryIds 待删除的 memory_item.id
     * @return 结论；空集合视为已达成（{@code SYNCED}）
     */
    public Attempt removeAttempt(Collection<Long> memoryIds) {
        List<Long> ids = positiveDistinct(memoryIds);
        // 空集合不算失败：没有点要删，等价于「已经一致」
        if (ids.isEmpty()) {
            return Attempt.synced();
        }
        MemoryVectorReadiness readiness = safeReadiness();
        if (readiness == null || !readiness.ready()) {
            log.debug("[MEMORY] 向量删除跳过 reason=not_ready ids={}", ids.size());
            return Attempt.deferred("记忆向量未就绪");
        }
        int chunk = Math.max(properties.getVectorDeleteBatchLimit(), 1);
        String collection = readiness.collectionName();
        if (ids.size() > chunk) {
            log.info("[MEMORY] 向量删除分批 ids={} batchLimit={} batches={}",
                    ids.size(), chunk, (ids.size() + chunk - 1) / chunk);
        }
        for (int from = 0; from < ids.size(); from += chunk) {
            List<Long> part = ids.subList(from, Math.min(from + chunk, ids.size()));
            Attempt outcome = removeChunk(collection, part);
            // 任一批失败就整体算失败：已删的批是幂等空操作，下一轮重删不会被拒
            if (outcome.outcome() != SyncOutcome.SYNCED) {
                return outcome;
            }
        }
        log.debug("[MEMORY] 向量删除成功 ids={}", ids.size());
        return Attempt.synced();
    }

    /**
     * 单批删除的有限次重试。成功返回 SYNCED；耗尽返回 FAILED 与短原因，不抛出。
     *
     * @param collection 记忆 collection
     * @param ids        本批主键（已去重、已限流）
     * @return 本批结论
     */
    private Attempt removeChunk(String collection, List<Long> ids) {
        int attempts = Math.max(properties.getVectorWriteMaxAttempts(), 1);
        long backoff = Math.max(properties.getVectorWriteRetryBackoffMs(), 0L);
        String reason = "未知失败";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                store.remove(collection, ids);
                return Attempt.synced();
            } catch (RuntimeException e) {
                reason = describe(e);
                if (attempt < attempts) {
                    log.debug("[MEMORY] 向量删除失败，准备重试 ids={} attempt={}/{} reason={}",
                            ids.size(), attempt, attempts, reason);
                    if (!sleepQuietly(backoff)) {
                        break;
                    }
                }
            }
        }
        // 耗尽：只日志。MySQL 侧的删除/过期清理已经提交，向量缺失由状态位 + 补同步兜住
        log.warn("[MEMORY] 向量删除最终失败 ids={} attempts={} reason={}", ids.size(), attempts, reason);
        return Attempt.failed(reason);
    }

    /**
     * 一次写入/删除的结论。所属域：memory。
     *
     * @param outcome   结论
     * @param reason    失败原因（脱敏截断，可直接落 vector_sync_error）；成功为 null
     * @param retryable 是否值得再试一次；确定性失败（维度不符）为 false
     */
    public record Attempt(SyncOutcome outcome, String reason, boolean retryable) {

        /** 成功：向量已写入 */
        public static Attempt synced() {
            return new Attempt(SyncOutcome.SYNCED, null, false);
        }

        /** 基础设施未就绪：调用方保持 PENDING，等下一次补同步 */
        public static Attempt deferred(String reason) {
            return new Attempt(SyncOutcome.DEFERRED, reason, false);
        }

        /** 失败且值得下一轮重试 */
        public static Attempt failed(String reason) {
            return new Attempt(SyncOutcome.FAILED, reason, true);
        }
    }

    /**
     * 写入结论枚举。所属域：memory。
     */
    public enum SyncOutcome {
        /** 已写入 */
        SYNCED,
        /** 未就绪，本轮跳过且不改状态位 */
        DEFERRED,
        /** 失败，调用方落 FAILED 并累加失败次数 */
        FAILED
    }

    /**
     * 取就绪快照；探测本身抛异常时按未就绪处理，绝不让池线程任务把异常甩到线程池。
     *
     * @return 快照；探测异常时返回 null
     */
    private MemoryVectorReadiness safeReadiness() {
        try {
            return support.readiness();
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 向量就绪探测异常，按未就绪跳过 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 把异常收敛成可落库的短原因：类名 + 消息，去换行、截断。
     * <p>
     * 只用于 {@code vector_sync_error}，<b>不含正文</b>；出站 Key 也不会出现在异常消息里
     * （客户端只报协议 / HTTP 状态），这里再加一道长度与换行清洗。
     *
     * @param e 出站异常
     * @return 短原因
     */
    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        String raw = e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        String flat = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= MAX_REASON_CHARS ? flat : flat.substring(0, MAX_REASON_CHARS);
    }

    /**
     * @return false 表示被中断，应停止继续重试
     */
    private static boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String clip(String text) {
        return text.length() <= MAX_EMBED_CHARS ? text : text.substring(0, MAX_EMBED_CHARS);
    }

    private static List<Long> positiveDistinct(Collection<Long> memoryIds) {
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
}
