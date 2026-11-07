package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryVectorPolicy;
import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LONG 向量「存量补同步」。所属域：memory 的 application。
 * <p>
 * 解决的是刀2 留下的缺口：双写是<b>提交后异步</b>的，进程崩溃 / 池拒绝 / Milvus 当时不可用
 * 都会让 MySQL 已提交但向量缺失。状态位（{@code vector_sync_status}）记录这件事，本类负责事后补齐。
 * <p>
 * 谁扫：MySQL 里仍有 LONG 行的 Agent（{@link MemoryItemRepository#listLongAgentIds()}），
 * 与当前记忆策略开关无关——策略关掉后历史条目仍需补齐，否则重新打开会缺召回。
 * <p>
 * 捞什么：未删除 + 启用 + 未过期 + 状态 PENDING/FAILED + 失败次数 &lt; 上限。
 * <p>
 * 一轮只推进有限条（{@code agenthub.memory.vector-reconcile-batch-limit}，默认 200）：
 * <ul>
 *   <li><b>不做启动全量扫</b>；触发由运维接口 {@code POST /api/memory/vector/reconcile} 或
 *       可选应用内定时（{@code vector-reconcile-schedule-enabled=false} 默认关，首次执行推迟一个周期）发起；</li>
 *   <li>同一进程内不允许两轮并发：{@code running} 标记 CAS 失败即直接返回，避免重复烧出站配额。</li>
 * </ul>
 * 降级：未就绪直接跳过整轮（不改状态位，等下一次）；单行失败落 FAILED + fail_count+1，
 * 达上限后停在 FAILED 只打日志；<b>任何情况都不回滚 MySQL、不抛给调用方</b>。
 * 幂等：同一 memory_id 的向量 upsert 幂等，重复跑不会产生重复行。
 */
@Component
public class MemoryVectorReconciler {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorReconciler.class);

    /** 失败原因落库上限，对齐 memory_item.vector_sync_error varchar(200) */
    private static final int MAX_RECORD_REASON_CHARS = 180;

    private final MemoryItemRepository items;
    private final MemoryVectorSupport support;
    private final MemoryVectorWriter writer;
    private final MemoryProperties properties;

    /**
     * 「本轮已跑」标记：同一时刻只允许一轮补同步，避免并发重复出站。
     * 只是进程内去重，不落库——幂等性由 memory_id upsert 与状态位保证。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param items      条目仓储（读候选 + 落状态位）
     * @param support    就绪快照（collection / 维度快照 / 平台级 Embedding）
     * @param writer     向量写入（返回失败原因供落 FAILED）
     * @param properties 单轮条数、失败上限
     */
    public MemoryVectorReconciler(MemoryItemRepository items, MemoryVectorSupport support,
                                  MemoryVectorWriter writer, MemoryProperties properties) {
        this.items = items;
        this.support = support;
        this.writer = writer;
        this.properties = properties;
    }

    /**
     * 跑一轮补同步。可被运维接口直接调用（同步执行，条数有硬上限）。
     *
     * @return 本轮统计；正在跑或未就绪时对应计数为 0，并由 {@code reason} 说明
     */
    public Summary runOnce() {
        // 并发保护：前一轮还在跑就立刻返回，绝不排队堆任务
        if (!running.compareAndSet(false, true)) {
            log.info("[MEMORY] 存量补同步跳过 reason=already_running");
            return new Summary("already_running", 0, 0, 0, 0);
        }
        try {
            return doRun();
        } finally {
            running.set(false);
        }
    }

    /**
     * 只读：是否已有一轮补同步在跑（CAS 标记）。模型中心据此拒绝「同步中切换平台级 Embedding」。
     *
     * @return true 表示本轮正在进行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 只读：当前待补同步条数（不含到失败上限的行），供调用方判断还要不要再触发一轮。
     *
     * @return 待同步条数；读失败返回 -1（不抛出）
     */
    public int pendingCount() {
        try {
            int maxFail = Math.max(properties.getVectorReconcileMaxFailCount(), 1);
            return items.countVectorSyncCandidates(maxFail, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 待补同步条数读取失败 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return -1;
        }
    }

    /**
     * 实际执行一轮：就绪判定 → <b>先删点（已删/停用/过期）</b> → 再按 Agent 补写正文向量。
     * <p>
     * 顺序刻意如此：删点便宜且能立刻收敛库内垃圾；把预算先给删点，避免「补写把预算吃光、
     * 过期点在库里越堆越多」。两个阶段各自受 batchLimit 约束，合计最多 2×batchLimit 次单条处理。
     */
    private Summary doRun() {
        MemoryVectorReadiness readiness = safeReadiness();
        if (readiness == null || !readiness.ready()) {
            // 未就绪不改任何状态位：等 Milvus / 平台级 Embedding 恢复后再补，避免把行误标 FAILED
            log.info("[MEMORY] 存量补同步跳过 reason=not_ready");
            return new Summary("not_ready", 0, 0, 0, 0);
        }
        int batchLimit = Math.max(properties.getVectorReconcileBatchLimit(), 1);
        int maxFail = Math.max(properties.getVectorReconcileMaxFailCount(), 1);
        LocalDateTime now = LocalDateTime.now();
        int scanned = 0;
        int synced = 0;
        int failed = 0;
        int skipped = 0;

        // 阶段一：删点。这些行在 MySQL 里已经过期/删除/停用，重新向量化是错的
        DeletePhase deleted = deletePoints(batchLimit, maxFail, now);
        scanned += deleted.scanned();
        synced += deleted.synced();
        failed += deleted.failed();
        skipped += deleted.skipped();

        // 阶段二：补写（只对仍应存在向量的行）
        int budget = batchLimit;
        List<Long> agentIds = items.listLongAgentIds();
        for (Long agentId : agentIds) {
            if (budget <= 0) {
                break;
            }
            List<MemoryItem> candidates;
            try {
                candidates = items.listVectorSyncCandidates(agentId, budget, maxFail, now);
            } catch (RuntimeException e) {
                // 单个 Agent 查询失败不拖垮整轮；下一轮还会再来
                log.warn("[MEMORY] 补同步候选查询失败 agentId={} type={} msg={}",
                        agentId, e.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            for (MemoryItem item : candidates) {
                if (budget <= 0) {
                    break;
                }
                budget--;
                scanned++;
                Outcome outcome = syncOne(item);
                if (outcome == Outcome.SYNCED) {
                    synced++;
                } else if (outcome == Outcome.FAILED) {
                    failed++;
                } else {
                    skipped++;
                }
            }
        }
        log.info("[MEMORY] 存量补同步完成 batchLimit={} deletedScanned={} agents={} scanned={} synced={} failed={} skipped={}",
                batchLimit, deleted.scanned(), agentIds.size(), scanned, synced, failed, skipped);
        return new Summary("done", scanned, synced, failed, skipped);
    }

    /**
     * 删点阶段：把「MySQL 已判定不该有向量」的行在向量库里删掉，并落终态。
     * <p>
     * 候选来自 {@link MemoryItemRepository#listVectorDeleteCandidates}（含 {@code deleted=1}，
     * 绕过 TableLogic），这正是刀8 之前缺失的那一段：过期/删除行的向量点没人删、也没人标终态。
     * <p>
     * 失败处置与双写一致：失败 → FAILED + 次数自增；未就绪 → 只回 PENDING（不计次数）。
     *
     * @param limit   本阶段上限
     * @param maxFail 失败次数上限（候选查询里已过滤）
     * @param now     当前时间
     * @return 本阶段统计
     */
    private DeletePhase deletePoints(int limit, int maxFail, LocalDateTime now) {
        List<Long> ids;
        try {
            ids = items.listVectorDeleteCandidates(now, limit, maxFail);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 删点候选查询失败 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return new DeletePhase(0, 0, 0, 0);
        }
        if (ids == null || ids.isEmpty()) {
            return new DeletePhase(0, 0, 0, 0);
        }
        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(ids);
        if (attempt != null && attempt.outcome() == MemoryVectorWriter.SyncOutcome.SYNCED) {
            markSynced(ids, "补同步删点成功");
            log.debug("[MEMORY] 补同步删点完成 ids={}", ids.size());
            return new DeletePhase(ids.size(), ids.size(), 0, 0);
        }
        if (attempt == null || attempt.outcome() == MemoryVectorWriter.SyncOutcome.DEFERRED) {
            // 中途变未就绪：只回 PENDING，不烧失败次数
            markStatus(ids, MemoryVectorSyncStatus.PENDING, "补同步删点延后");
            return new DeletePhase(ids.size(), 0, 0, ids.size());
        }
        String reason = attempt.reason();
        try {
            items.bumpVectorSyncFail(ids, MemoryVectorSyncStatus.FAILED,
                    reason == null ? "删点失败" : shorten(reason));
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 删点状态落库失败 ids={} type={} msg={}",
                    ids.size(), e.getClass().getSimpleName(), e.getMessage());
        }
        log.warn("[MEMORY] 补同步删点失败 ids={} reason={}", ids.size(), reason);
        return new DeletePhase(ids.size(), 0, ids.size(), 0);
    }

    /**
     * 只改状态位的静默包装（不改失败次数）。
     *
     * @param ids    主键
     * @param status 目标状态
     * @param action 日志用动作名
     */
    private void markStatus(List<Long> ids, MemoryVectorSyncStatus status, String action) {
        try {
            items.markVectorSyncStatus(ids, status);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 状态落库失败 action={} ids={} type={} msg={}",
                    action, ids.size(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 删点阶段的统计。所属域：memory 的 application。
     *
     * @param scanned 候选条数
     * @param synced  成功删点条数
     * @param failed  失败条数
     * @param skipped 跳过条数（未就绪）
     */
    private record DeletePhase(int scanned, int synced, int failed, int skipped) {
    }

    /**
     * 同步一条：空正文/已过期/停用 → 走删点终态（不出站 Embedding）；
     * 其余按写入结论落 SYNCED / FAILED / 保持 PENDING。
     * <p>
     * 刀8 的守卫：候选查询本身只取「有效未过期」行，但查询与逐条处理之间存在时间窗
     * （这一轮跑得慢、行刚好过期）。此处再判一次，保证<b>绝不对已过期行重新 upsert 正文</b>——
     * 否则会写出一个永远召不回、又留在库里的点。
     *
     * @param item 候选行
     * @return 单条结论
     */
    private Outcome syncOne(MemoryItem item) {
        if (item == null || item.getId() == null) {
            return Outcome.SKIPPED;
        }
        // 时间窗守卫：不该有向量的行改走删点，绝不 Embedding
        if (!MemoryVectorPolicy.shouldHaveVector(item, LocalDateTime.now())) {
            return deleteOne(item.getId());
        }
        String content = item.getContent();
        // 空正文不向量化：落 SYNCED 终态，否则每轮都会被捞到、永远失败
        if (content == null || content.isBlank()) {
            markSynced(item.getId(), "空正文跳过");
            return Outcome.SYNCED;
        }
        MemoryVectorWriter.Attempt attempt;
        try {
            attempt = writer.attempt(item);
        } catch (RuntimeException e) {
            // 写入器已收敛所有出站异常，这里再兜一层：单条异常不得中断整轮
            attempt = MemoryVectorWriter.Attempt.failed(
                    shorten(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        if (attempt.outcome() == MemoryVectorWriter.SyncOutcome.SYNCED) {
            markSynced(item.getId(), "补同步成功");
            return Outcome.SYNCED;
        }
        if (attempt.outcome() == MemoryVectorWriter.SyncOutcome.DEFERRED) {
            // 中途变未就绪：不改状态位，下一轮再来（不能误标 FAILED 烧掉失败次数）
            return Outcome.SKIPPED;
        }
        int nextCount = (item.getVectorSyncFailCount() == null ? 0 : item.getVectorSyncFailCount()) + 1;
        markFailed(item.getId(), nextCount, attempt.reason());
        return Outcome.FAILED;
    }

    /**
     * 单条删点：用于时间窗守卫命中（候选查出来时还在有效期内，处理时已过期/停用）。
     *
     * @param memoryId 主键
     * @return 单条结论
     */
    private Outcome deleteOne(Long memoryId) {
        MemoryVectorWriter.Attempt attempt = writer.removeAttempt(List.of(memoryId));
        if (attempt != null && attempt.outcome() == MemoryVectorWriter.SyncOutcome.SYNCED) {
            markSynced(memoryId, "转删点成功");
            return Outcome.SYNCED;
        }
        if (attempt == null || attempt.outcome() == MemoryVectorWriter.SyncOutcome.DEFERRED) {
            // 未就绪：保持原状态，下一轮再判
            return Outcome.SKIPPED;
        }
        try {
            items.bumpVectorSyncFail(List.of(memoryId), MemoryVectorSyncStatus.FAILED,
                    shorten(attempt.reason()));
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 删点状态落库失败 memoryId={} type={} msg={}",
                    memoryId, e.getClass().getSimpleName(), e.getMessage());
        }
        return Outcome.FAILED;
    }

    /**
     * 落 SYNCED（批量）；失败只日志（下一轮还会再捞到，不影响正确性）。
     *
     * @param ids    主键
     * @param action 日志用动作名
     */
    private void markSynced(List<Long> ids, String action) {
        try {
            items.markVectorSync(ids, MemoryVectorSyncStatus.SYNCED, 0, null);
            log.debug("[MEMORY] 补同步{} ids={}", action, ids.size());
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 补同步状态落库失败 ids={} status=SYNCED type={} msg={}",
                    ids.size(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 落 SYNCED；失败只日志（下一轮还会再捞到，不影响正确性）。
     */
    private void markSynced(Long memoryId, String action) {
        try {
            items.markVectorSync(List.of(memoryId), MemoryVectorSyncStatus.SYNCED, 0, null);
            log.debug("[MEMORY] 补同步{} memoryId={}", action, memoryId);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 补同步状态落库失败 memoryId={} status=SYNCED type={} msg={}",
                    memoryId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 落 FAILED + 计数；达上限时额外打 warn，之后不再被捞取。
     */
    private void markFailed(Long memoryId, int failCount, String reason) {
        try {
            items.markVectorSync(List.of(memoryId), MemoryVectorSyncStatus.FAILED, failCount, shorten(reason));
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 补同步状态落库失败 memoryId={} status=FAILED type={} msg={}",
                    memoryId, e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        int maxFail = Math.max(properties.getVectorReconcileMaxFailCount(), 1);
        if (failCount >= maxFail) {
            // 到上限只日志：不再定时重炸，也不回滚 MySQL
            log.warn("[MEMORY] 补同步放弃 memoryId={} failCount={} reason={}", memoryId, failCount, reason);
        } else {
            log.warn("[MEMORY] 补同步失败，等待下一轮 memoryId={} failCount={}/{} reason={}",
                    memoryId, failCount, maxFail, reason);
        }
    }

    /**
     * 取就绪快照；探测异常按未就绪处理（整轮跳过，不改状态位）。
     */
    private MemoryVectorReadiness safeReadiness() {
        try {
            return support.readiness();
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 补同步就绪探测异常，本轮跳过 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 失败原因落库前截断，对齐 memory_item.vector_sync_error varchar(200)，并去掉换行。
     *
     * @param reason 原始原因
     * @return 可直接落库的短文本
     */
    private static String shorten(String reason) {
        if (reason == null) {
            return null;
        }
        String flat = reason.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= MAX_RECORD_REASON_CHARS ? flat : flat.substring(0, MAX_RECORD_REASON_CHARS);
    }

    /**
     * 单条结论。只用来说明本轮统计，不落库。
     */
    private enum Outcome {
        /** 已写入或无需向量（空正文），状态位为 SYNCED */
        SYNCED,
        /** 写入失败，状态位为 FAILED */
        FAILED,
        /** 参数缺失等无法处理的情况 */
        SKIPPED
    }

    /**
     * 一轮补同步的统计。所属域：memory。
     *
     * @param reason  done / not_ready / already_running
     * @param scanned 本轮扫到的候选条数
     * @param synced  成功（含空正文终态）条数
     * @param failed  失败条数
     * @param skipped 跳过条数
     */
    public record Summary(String reason, int scanned, int synced, int failed, int skipped) {
    }
}
