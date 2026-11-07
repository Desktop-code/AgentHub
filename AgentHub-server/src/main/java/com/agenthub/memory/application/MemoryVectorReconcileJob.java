package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存量补同步的应用内定时。所属域：memory 的 application。
 * <p>
 * 职责只有一件事：按固定间隔调用 {@link MemoryVectorReconciler#runOnce()}。
 * <b>刻意不与 {@link MemoryScheduleConfig} 合并</b>——那个是「过期行清理」，本类是「向量补同步」，
 * 两者的开关、频率、失败后果都不同，混在一个方法里会让任一侧改配置都影响另一侧。
 * <p>
 * 与手动接口的关系：{@code POST /api/memory/vector/reconcile} 与本定时<b>共用同一个 runOnce()</b>，
 * 因此 CAS 并发互斥 / 未就绪整轮跳过 / 单轮 ≤200 / fail_count&lt;3 全部自动复用，
 * 本类不再重复实现任何状态机。
 * <p>
 * 边界：
 * <ul>
 *   <li>开关 {@code agenthub.memory.vector-reconcile-schedule-enabled} 默认 false，
 *       关闭时本方法立即返回，不查库、不出站（手动接口仍可用）；</li>
 *   <li>首次执行推迟一个间隔（默认 30 分钟）：<b>不在启动时全量扫</b>，避免与启动自检、冷启动抢资源；</li>
 *   <li>未就绪 / 已在跑 / 无候选 → 由 runOnce 内部 skip 并返回 reason，本类只打一行 debug；</li>
 *   <li>异常一律吞掉：定时线程抛异常会被调度器记 error 并继续下一轮，但会把栈刷进日志，
 *       这里收敛成一行 warn，保持「记忆向量出问题不影响服务」的口径。</li>
 * </ul>
 * 不使用 XXL-JOB / Quartz：单实例 jar 部署即可自洽，多实例时由数据库行状态与 CAS 保证幂等。
 */
@Component
public class MemoryVectorReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorReconcileJob.class);

    private final MemoryVectorReconciler reconciler;
    private final MemoryVectorRebuildState rebuildState;
    private final MemoryProperties properties;

    /**
     * @param reconciler   补同步编排（与手动接口同一个 bean）
     * @param rebuildState 重建状态（本轮结束后按待补条数收口）
     * @param properties   开关与间隔
     */
    public MemoryVectorReconcileJob(MemoryVectorReconciler reconciler,
                                    MemoryVectorRebuildState rebuildState,
                                    MemoryProperties properties) {
        this.reconciler = reconciler;
        this.rebuildState = rebuildState;
        this.properties = properties;
    }

    /**
     * 定时跑一轮补同步。fixedDelay 语义：上一轮<b>结束后</b>再等一个间隔，不会因单轮耗时而叠加堆积。
     * <p>
     * initialDelay 与 fixedDelay 取同一个值：启动后先等一个周期，避免「启动即全量扫」。
     */
    @Scheduled(
            fixedDelayString = "${agenthub.memory.vector-reconcile-delay-ms:1800000}",
            initialDelayString = "${agenthub.memory.vector-reconcile-delay-ms:1800000}")
    public void reconcilePeriodically() {
        // 开关关（默认）：一行 debug 都不必刷，直接返回
        if (!properties.isVectorReconcileScheduleEnabled()) {
            return;
        }
        try {
            MemoryVectorReconciler.Summary summary = reconciler.runOnce();
            // 重建态收口：全量 PENDING 补完才放开平台级 Embedding 变更
            boolean drained = rebuildState.clearIfDrained(reconciler.pendingCount());
            // runOnce 已按 done / not_ready / already_running 打过 info/warn，这里只留一行可追踪
            log.debug("[MEMORY] 定时补同步 reason={} scanned={} synced={} failed={} skipped={} released={}",
                    summary.reason(), summary.scanned(), summary.synced(), summary.failed(),
                    summary.skipped(), drained);
        } catch (RuntimeException e) {
            // 定时线程不得因单轮异常中断后续调度；下一轮会自然重来
            log.warn("[MEMORY] 定时补同步异常，本轮放弃 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
