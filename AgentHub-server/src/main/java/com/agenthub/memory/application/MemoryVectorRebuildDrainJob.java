package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「重建未完成 / 平台级变更留下待补」期间的自动续补。所属域：memory 的 application。
 * <p>
 * 为什么需要它：重建接口与平台级换绑都只做<b>有界一轮</b>补同步（单轮 ≤ {@code vector-reconcile-batch-limit}），
 * 存量多时必须一轮轮推进。之前剩余部分只能靠人守在页面上反复点「继续补同步」，
 * 页面一刷新、菜单一切换，观感就是「断开了 / 还要手工点」。
 * <p>
 * 本任务把「续补」搬回服务端：只要进程仍持有续补请求
 * （{@link MemoryVectorRebuildState#isDrainWanted()}：重建未完成，或平台级换绑留下待补），
 * 就按短间隔继续推进，直到待补清空自动收口。页面刷新 / 切菜单 / 关掉浏览器都不影响进度，
 * 回来时 status 里 {@code rebuilding|draining} + {@code pendingSync=N} 仍能立刻看到「进行中」。
 * <p>
 * 线程契约（关键）：调度线程<b>只做闸门判断与投递</b>，真正的一轮补同步跑在
 * {@code memoryTaskExecutor} 池线程上。原因：单轮可能包含上百次 Embedding 出站，
 * 若直接跑在 Spring 默认单线程调度器上，会把过期清理、空闲批抽、计费汇总全部堵住。
 * <p>
 * 边界（与既有出口完全一致，不自建一套）：
 * <ul>
 *   <li><b>限流</b>：复用 {@link MemoryVectorReconciler#runOnce()} 的 CAS 与单轮上限；
 *       本类再用 {@code inFlight} 保证同一时刻只投递一轮，不与手动轮并发；</li>
 *   <li><b>退避</b>：单轮零进展（Embedding 故障 / 未就绪）时按 2 倍退避到
 *       {@code vector-rebuild-drain-max-backoff-ms}，不每 3 秒重炸出站；一有进展立即回到基础间隔；</li>
 *   <li><b>降级</b>：未就绪时 runOnce 内部整轮跳过、不改状态位，本任务只退避重试；
 *       池满被拒时丢掉这一轮（下一次 tick 再来），绝不退回调度线程执行；</li>
 *   <li><b>不抢别处</b>：没有续补请求时<b>一次库都不查</b>，与全局补同步定时
 *       （{@link MemoryVectorReconcileJob}，默认关）互不影响；</li>
 *   <li><b>幂等</b>：重复跑同一 memory_id 的向量 upsert 幂等，状态位按结论落。</li>
 * </ul>
 * 单实例 jar 部署自洽；多实例时依赖 DB 行状态与 CAS 收敛，不引入分布式锁。
 */
@Component
public class MemoryVectorRebuildDrainJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorRebuildDrainJob.class);

    private final MemoryVectorReconciler reconciler;
    private final MemoryVectorRebuildState rebuildState;
    private final MemoryProperties properties;
    private final ThreadPoolTaskExecutor memoryTaskExecutor;

    /** 是否已有一轮续补在池线程上执行：防止固定间隔重复投递堆任务 */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    /** 下一次允许推进的毫秒时刻；0 表示立即可跑 */
    private volatile long nextAttemptAtMs;
    /** 连续零进展轮数，仅用于指数退避与日志 */
    private volatile int stalledRounds;

    /**
     * @param reconciler        补同步编排（与手动接口 / 全局定时同一个 bean，不重复实现状态机）
     * @param rebuildState      重建状态（本任务只在 rebuilding=true 时工作）
     * @param properties        开关、间隔与退避上限
     * @param memoryTaskExecutor 记忆专用池（有界队列 + Abort），承载真正的一轮补同步
     */
    public MemoryVectorRebuildDrainJob(MemoryVectorReconciler reconciler,
                                       MemoryVectorRebuildState rebuildState,
                                       MemoryProperties properties,
                                       @Qualifier("memoryTaskExecutor") ThreadPoolTaskExecutor memoryTaskExecutor) {
        this.reconciler = reconciler;
        this.rebuildState = rebuildState;
        this.properties = properties;
        this.memoryTaskExecutor = memoryTaskExecutor;
    }

    /**
     * 按短间隔尝试投递一轮续补。<b>只有处于「重建未完成」或「平台级变更留下待补」时才真正执行</b>。
     * <p>
     * 闸门顺序有意如此：先看开关（一次配置读，最便宜）→ 再看续补请求（内存原子读）
     * → 看退避时刻 → 看是否已有轮次在跑，保证空闲期没有 SQL、没有出站、没有线程占用。
     */
    @Scheduled(
            fixedDelayString = "${agenthub.memory.vector-rebuild-drain-delay-ms:3000}",
            initialDelayString = "${agenthub.memory.vector-rebuild-drain-delay-ms:3000}")
    public void drainDuringRebuild() {
        if (!properties.isVectorRebuildDrainEnabled()) {
            return;
        }
        if (!rebuildState.isDrainWanted()) {
            // 空闲期：把退避状态复位，下一次重建 / 换绑后从基础间隔开始
            resetBackoff();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAttemptAtMs) {
            return;
        }
        // 已有另一轮补同步在跑（手动接口 / 全局定时）：不排队，下个 tick 再看
        if (reconciler.isRunning()) {
            return;
        }
        // 本轮续补仍在池线程上执行：不重复投递
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            // 绝不在这里同步 runOnce：上百次 Embedding 出站会堵死单线程调度器
            memoryTaskExecutor.execute(this::runOneRound);
        } catch (RuntimeException e) {
            // 池满被拒（AbortPolicy）或执行器已关闭：放开标记并退避，下一次 tick 再来
            inFlight.set(false);
            applyBackoff(null, -1);
            log.warn("[MEMORY] 重建自动续补投递失败，本轮放弃 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 是否处于自动续补中（供状态页/排障使用；不参与业务判定）。
     *
     * @return true 表示当前有重建未完成 / 平台级变更待补，且已开启自动续补
     */
    public boolean isDraining() {
        return properties.isVectorRebuildDrainEnabled() && rebuildState.isDrainWanted();
    }

    /**
     * 在记忆池线程上跑一轮：推进补同步 → 按待补条数收口或退避。
     * <p>
     * 收口判定与手动接口、全局定时完全一致（都用 {@link MemoryVectorRebuildState#clearIfDrained(int)}），
     * 保证「清空即放开平台级变更锁」只有一处语义。
     */
    private void runOneRound() {
        try {
            // 投递到执行之间可能已被手动接口补齐：再判一次，避免空跑出站
            if (!rebuildState.isDrainWanted()) {
                resetBackoff();
                return;
            }
            MemoryVectorReconciler.Summary summary = reconciler.runOnce();
            int pending = reconciler.pendingCount();
            // 先按待补条数判定收口：清空即放开平台级变更锁 / 撤掉续补请求
            boolean released = rebuildState.clearIfDrained(pending);
            if (pending == 0) {
                resetBackoff();
                // 达到失败上限的行不再计入待补：这里把本轮失败数留痕，避免「显示完成但仍有缺口」无据可查
                if (summary.failed() > 0) {
                    log.warn("[MEMORY] 向量自动续补收口 pending=0 但本轮失败 failed={}（达上限的行停在 FAILED，见 vector_sync_error）",
                            summary.failed());
                } else {
                    log.info("[MEMORY] 向量自动续补完成 pending=0 本轮 synced={} 重建锁释放={}",
                            summary.synced(), released);
                }
                return;
            }
            applyBackoff(summary, pending);
        } catch (RuntimeException e) {
            // 池线程不得把异常抛给执行器；按零进展退避，避免日志被刷屏
            applyBackoff(null, -1);
            log.warn("[MEMORY] 重建自动续补异常，本轮放弃 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
        } finally {
            inFlight.set(false);
        }
    }

    /**
     * 根据本轮结论推进或退避：
     * <ul>
     *   <li>有进展（synced&gt;0）→ 回到基础间隔，保证大存量能连续推进；</li>
     *   <li>零进展（未就绪 / 全部失败 / 异常）→ 指数退避，最多到配置上限。</li>
     * </ul>
     *
     * @param summary 本轮统计；异常或投递失败传 null
     * @param pending 本轮结束后待补条数；-1 表示读取失败
     */
    private void applyBackoff(MemoryVectorReconciler.Summary summary, int pending) {
        long base = baseDelayMs();
        long max = maxBackoffMs();
        boolean progress = summary != null && summary.synced() > 0;
        if (progress) {
            stalledRounds = 0;
            long delay = nextDelayMs(base, max, 0, true);
            nextAttemptAtMs = System.currentTimeMillis() + delay;
            log.info("[MEMORY] 重建自动续补 pending={} scanned={} synced={} failed={} reason={} nextDelayMs={}",
                    pending, summary.scanned(), summary.synced(), summary.failed(), summary.reason(), delay);
            return;
        }
        // 指数退避：stalledRounds 只增到上限所需的位数即够
        int stalled = Math.min(stalledRounds + 1, 16);
        stalledRounds = stalled;
        long delay = nextDelayMs(base, max, stalled, false);
        nextAttemptAtMs = System.currentTimeMillis() + delay;
        log.info("[MEMORY] 重建自动续补等待 pending={} stalledRounds={} nextDelayMs={} reason={}",
                pending, stalled, delay, summary == null ? "submit_failed_or_exception" : summary.reason());
    }

    /**
     * 计算下一次尝试的延迟。抽成纯函数便于单测「退避曲线」，也让续补逻辑保持短小。
     * <ul>
     *   <li>有进展：永远回到基础间隔（大存量需要连贯推进，不能残留上一轮的退避）；</li>
     *   <li>零进展：基础间隔 × 2^(stalled-1)，压到 {@code max} 为止。</li>
     * </ul>
     *
     * @param base          基础间隔（毫秒，已兜底为正数）
     * @param max           退避上限（毫秒，已保证 ≥ base）
     * @param stalledRounds 连续零进展轮数；有进展时传 0
     * @param progress      本轮是否有进展
     * @return 下一次尝试的延迟（毫秒）
     */
    static long nextDelayMs(long base, long max, int stalledRounds, boolean progress) {
        if (progress) {
            return base;
        }
        long delay = base;
        for (int i = 1; i < stalledRounds && delay < max; i++) {
            delay = Math.min(delay * 2, max);
        }
        return Math.min(delay, max);
    }

    /**
     * 基础间隔：配置为 0 或负数时按 1 秒兜底，避免 0 间隔把调度线程打满。
     */
    private long baseDelayMs() {
        long configured = properties.getVectorRebuildDrainDelayMs();
        return configured > 0 ? configured : 1_000L;
    }

    /**
     * 退避上限：必须 ≥ 基础间隔；配置异常时退回 30 秒。
     */
    private long maxBackoffMs() {
        long configured = properties.getVectorRebuildDrainMaxBackoffMs();
        long base = baseDelayMs();
        if (configured <= 0) {
            return Math.max(base, 30_000L);
        }
        return Math.max(configured, base);
    }

    /**
     * 复位退避：下一次需要续补时立刻按基础间隔推进。
     */
    private void resetBackoff() {
        stalledRounds = 0;
        nextAttemptAtMs = 0L;
    }
}
