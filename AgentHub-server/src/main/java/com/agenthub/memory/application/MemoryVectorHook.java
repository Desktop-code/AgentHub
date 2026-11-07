package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryVectorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LONG 记忆双写的唯一挂载点。所属域：memory 的 application。
 * <p>
 * 为什么要一个统一 hook：LONG 的写入出口有显式抽取、会话结束批抽、摘要顺带抽、控制台人工新增/编辑，
 * 删除出口有手工删除、超限淘汰、过期清理。若每条路径各自调「embed + upsert」，迟早出现某条路径漏写或
 * 语义不一致。这里把「判断是否该写 / 何时投递 / 投递到哪个池」收口成两个方法，各出口只调一行。
 * <p>
 * 线程与事务契约（硬约束）：
 * <ol>
 *   <li><b>提交后</b>才投递向量出站：在事务内调用时注册 {@link TransactionSynchronization#afterCommit()}，
 *       MySQL 回滚则永不产生向量写入；无事务时立即投递；</li>
 *   <li>向量写入只在 {@code memoryTaskExecutor} 池线程上执行，<b>绝不同步</b>跑在请求线程
 *       （Embedding + Milvus 都是出站，同步会吃掉首字延迟）；</li>
 *   <li>池拒绝（有界队列 + Abort）只打日志，不阻塞调用方、不回滚 MySQL；</li>
 *   <li>向量失败不回滚 MySQL：MySQL 是事实来源，向量是可重建的派生数据。</li>
 * </ol>
 * 状态位（刀5 / 刀8）：
 * <ul>
 *   <li>{@code afterSaved}：<b>同一事务内</b>先把 {@code vector_sync_status} 置 PENDING，
 *       保证「正文已改但向量没跟上」这件事随内容一起提交，进程中途崩溃也不会漏；</li>
 *   <li>提交后由池线程写入；成功 → SYNCED；失败 → FAILED + 次数；未就绪 → 保持 PENDING 等补同步；</li>
 *   <li>{@code afterRemoved}：<b>先删点，再按结论落状态</b>——成功（含点本就不存在）→ SYNCED 终态；
 *       未就绪 → PENDING（不烧三振，等补同步按「删点」分支收口）；失败 → FAILED + 次数 + 短原因。
 *       这一步是刀8 的核心：过期/删除后 MySQL 已完成，向量点不能只靠检索 filter 挡着。</li>
 * </ul>
 * 只处理 {@link MemoryScope#LONG}：SHORT 走 Redis/MySQL 双写，AGENT 不参与向量召回。
 */
@Component
public class MemoryVectorHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorHook.class);

    private final ThreadPoolTaskExecutor memoryTaskExecutor;
    private final MemoryVectorWriter writer;
    private final MemoryItemRepository items;

    /**
     * @param memoryTaskExecutor 显式记忆专用池（有界队列 + Abort），与显式抽取共用
     * @param writer             向量写入/删除
     * @param items              只用于落向量同步状态位（不改业务列）
     */
    public MemoryVectorHook(@Qualifier("memoryTaskExecutor") ThreadPoolTaskExecutor memoryTaskExecutor,
                            MemoryVectorWriter writer, MemoryItemRepository items) {
        this.memoryTaskExecutor = memoryTaskExecutor;
        this.writer = writer;
        this.items = items;
    }

    /**
     * 一条 LONG 已成功写库（新增或更新）后调用。按提交时的数据库状态决定写向量还是删向量。
     * <p>
     * 停用 / 已过期 / 正文为空都视为「不该有向量」：走删除，避免召回命中失效内容。
     * 非 LONG 直接返回，不产生任何投递。
     *
     * @param item 落库后的条目（建议传重新查回的整行，避免字段缺省）
     */
    public void afterSaved(MemoryItem item) {
        if (item == null || item.getId() == null || !isLong(item)) {
            return;
        }
        MemoryItem snapshot = copyOf(item);
        // 与内容同事务落 PENDING：先标「待同步」，再异步真正写入。
        // 标状态失败不影响主流程，但必须日志（否则该行永远不会被补同步捞到）。
        markQuietly(List.of(snapshot.getId()), MemoryVectorSyncStatus.PENDING, 0, null, "标记待同步");
        // 交给池线程前先拷一份：调用方后续可能复用/修改该对象
        submit(() -> {
            LocalDateTime now = LocalDateTime.now();
            if (!MemoryVectorPolicy.shouldHaveVector(snapshot, now)) {
                // 停用 / 已过期 / 空正文：本行不该有向量，删点后落终态（删失败交给补同步）
                applyRemove(List.of(snapshot.getId()), writer.removeAttempt(List.of(snapshot.getId())));
                return;
            }
            // 写入结论决定状态位：成功 → SYNCED；失败 → FAILED + 次数；未就绪 → 保持 PENDING 等补同步
            MemoryVectorWriter.Attempt attempt = writer.attempt(snapshot);
            applyAttempt(snapshot, attempt);
        });
    }

    /**
     * 把写入结论落成状态位。未就绪时什么都不做——行保持 PENDING，等存量补同步接手。
     *
     * @param item    本次写入的条目（含库中失败次数）
     * @param attempt 写入结论
     */
    private void applyAttempt(MemoryItem item, MemoryVectorWriter.Attempt attempt) {
        if (attempt == null || attempt.outcome() == MemoryVectorWriter.SyncOutcome.DEFERRED) {
            return;
        }
        if (attempt.outcome() == MemoryVectorWriter.SyncOutcome.SYNCED) {
            markQuietly(List.of(item.getId()), MemoryVectorSyncStatus.SYNCED, 0, null, "双写成功");
            return;
        }
        int nextCount = (item.getVectorSyncFailCount() == null ? 0 : item.getVectorSyncFailCount()) + 1;
        log.warn("[MEMORY] 双写失败 memoryId={} failCount={} reason={}",
                item.getId(), nextCount, attempt.reason());
        markQuietly(List.of(item.getId()), MemoryVectorSyncStatus.FAILED, nextCount,
                attempt.reason(), "双写失败");
    }

    /**
     * 若干 LONG 行已被逻辑删除 / 淘汰 / 过期清理后调用。
     * <p>
     * 这是刀8 的联删出口：MySQL 侧已经完成（删除/过期是既成事实），这里负责让向量库跟上。
     * <b>异步</b>：绝不阻塞请求线程或 MySQL 事务；删点失败不回滚 MySQL。
     * <p>
     * 不做 scope 过滤：memory_item.id 全局唯一，对不存在的向量 id 删除是幂等空操作。
     * 传进来的 SHORT / AGENT id 不会被误改状态位——{@code markVectorSync*} 的 SQL 已硬锁 {@code scope='LONG'}。
     *
     * @param memoryIds 被删除的 memory_item.id
     */
    public void afterRemoved(Collection<Long> memoryIds) {
        List<Long> ids = positiveDistinct(memoryIds);
        if (ids.isEmpty()) {
            return;
        }
        // 先删点再落状态：成功才是 SYNCED，失败要留 FAILED 让补同步接手（刀8 拍板）
        submit(() -> applyRemove(ids, writer.removeAttempt(ids)),
                // 投递被拒 → 没人会删这个点，拉回 PENDING 交给补同步的删点分支
                () -> markStatusQuietly(ids, MemoryVectorSyncStatus.PENDING, "联删投递被拒"));
    }

    /**
     * 把联删结论落成状态位。
     * <ul>
     *   <li>SYNCED（含点本就不存在）→ 终态 SYNCED，失败次数清零；</li>
     *   <li>DEFERRED（未就绪）→ 只改回 PENDING，<b>不</b>计失败次数：一次断网不该把三振用完；</li>
     *   <li>FAILED → FAILED + 次数自增 + 短原因，由补同步的「删点」分支继续收口。</li>
     * </ul>
     *
     * @param ids     本次联删的主键
     * @param attempt 删除结论；null 视为失败（不静默当成功）
     */
    private void applyRemove(List<Long> ids, MemoryVectorWriter.Attempt attempt) {
        if (attempt != null && attempt.outcome() == MemoryVectorWriter.SyncOutcome.SYNCED) {
            markQuietly(ids, MemoryVectorSyncStatus.SYNCED, 0, null, "联删成功");
            return;
        }
        if (attempt == null || attempt.outcome() == MemoryVectorWriter.SyncOutcome.DEFERRED) {
            // 未就绪：保持「待处理」，补同步就绪后按过期/已删分支删点
            markStatusQuietly(ids, MemoryVectorSyncStatus.PENDING, "联删延后");
            return;
        }
        log.warn("[MEMORY] 联删失败 ids={} reason={}", ids.size(), attempt.reason());
        bumpFailQuietly(ids, attempt.reason());
    }

    /**
     * 落 FAILED 并让失败次数 +1；异常只日志，绝不打断主流程。
     *
     * @param ids    主键
     * @param reason 失败原因（脱敏短文本）
     */
    private void bumpFailQuietly(List<Long> ids, String reason) {
        try {
            items.bumpVectorSyncFail(ids, MemoryVectorSyncStatus.FAILED,
                    reason == null ? "删除失败" : reason);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 联删状态落库失败 ids={} status=FAILED type={} msg={}",
                    ids.size(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 只改状态位；异常只日志。
     *
     * @param ids    主键
     * @param status 目标状态
     * @param action 日志用动作名
     */
    private void markStatusQuietly(List<Long> ids, MemoryVectorSyncStatus status, String action) {
        try {
            items.markVectorSyncStatus(ids, status);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 向量同步状态落库失败 action={} ids={} status={} type={} msg={}",
                    action, ids.size(), status, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 落状态位；任何失败只日志，绝不把异常带给主业务流程。
     *
     * @param ids       主键
     * @param status    目标状态
     * @param failCount 失败次数（成功 / 终态传 0）
     * @param error     失败原因（成功 / 终态传 null）
     * @param action    日志用动作名
     */
    private void markQuietly(Collection<Long> ids, MemoryVectorSyncStatus status, int failCount,
                             String error, String action) {
        try {
            items.markVectorSync(ids, status, failCount, error);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 向量同步状态落库失败 action={} ids={} status={} type={} msg={}",
                    action, ids == null ? 0 : ids.size(), status, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static boolean isLong(MemoryItem item) {
        return MemoryScope.LONG.name().equals(item.getScope());
    }

    /**
     * 提交后投递。事务内注册 afterCommit，事务外立即投递。
     * <p>
     * 只用 {@code isSynchronizationActive()} 判断：同步上下文开启即说明在 Spring 事务作用域内，
     * 此时必须等 afterCommit，避免 MySQL 回滚后仍写了向量。
     */
    private void submit(Runnable task) {
        submit(task, null);
    }

    /**
     * 事务提交后投递；没有事务时立即投递。池拒绝时执行 {@code onReject}（可为 null）。
     * <p>
     * 为什么需要 onReject：删点任务被拒绝就再也没人删这个点了。调用方据此把行拉回 PENDING，
     * 让补同步的「删点」分支接手；否则行停在 SYNCED，向量点会永久滞留。
     *
     * @param task     要执行的任务
     * @param onReject 投递被拒绝时的补偿动作，可为 null
     */
    private void submit(Runnable task, Runnable onReject) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(task, onReject);
                }
            });
            return;
        }
        dispatch(task, onReject);
    }

    /**
     * 投递到有界池。拒绝时只记日志 + 补偿：向量是派生数据，不能因此回滚已提交的 MySQL 操作。
     *
     * @param task     要执行的任务
     * @param onReject 拒绝后的补偿动作，可为 null
     */
    private void dispatch(Runnable task, Runnable onReject) {
        try {
            memoryTaskExecutor.execute(task);
        } catch (TaskRejectedException ex) {
            log.warn("[MEMORY] 向量双写投递被拒绝 reason=pool_saturated type={}",
                    ex.getClass().getSimpleName());
            if (onReject != null) {
                try {
                    onReject.run();
                } catch (RuntimeException e) {
                    log.warn("[MEMORY] 投递拒绝补偿失败 type={} msg={}",
                            e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 拷贝一份最小快照：池线程可能在事务收尾后才执行，避免读到调用方后续修改的字段。
     * <p>
     * 字段必须与刀7 写入行所需一一对应：漏拷 {@code importance} 会让向量侧永远写 0，
     * 召回按重要度重排时全部并列，属于「写入成功但语义丢失」的隐形错误。
     */
    private static MemoryItem copyOf(MemoryItem src) {
        MemoryItem copy = new MemoryItem();
        copy.setId(src.getId());
        copy.setAgentId(src.getAgentId());
        copy.setUserId(src.getUserId());
        copy.setScope(src.getScope());
        copy.setFactKey(src.getFactKey());
        copy.setContent(src.getContent());
        copy.setStatus(src.getStatus());
        copy.setImportance(src.getImportance());
        copy.setExpiredTime(src.getExpiredTime());
        // 失败次数随快照带下去：池线程据此累加，避免多读一次库
        copy.setVectorSyncFailCount(src.getVectorSyncFailCount());
        return copy;
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
