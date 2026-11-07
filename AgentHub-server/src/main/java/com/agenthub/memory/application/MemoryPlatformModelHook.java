package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.model.domain.model.ModelCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 平台级记忆模型变更的挂载点。所属域：memory 的 application。
 * <p>
 * 调用方：模型中心「设为平台级 / 取消平台级 / 换绑」与连通测试写回维度快照。
 * 本类只做三件事：
 * <ol>
 *   <li>同步失效平台级实例解析缓存与就绪快照（请求返回后即可见新配置）；</li>
 *   <li><b>提交后</b>在记忆池线程按新实例重新 ensure collection（维度来自连通测试快照，无平台级则不 ensure）；</li>
 *   <li>Embedding 变更且已就绪时，再跑<b>有界一轮</b>补同步（复用 {@link MemoryVectorReconciler#runOnce()}）。</li>
 * </ol>
 * 硬约束：绝不做启动全量扫、绝不同步阻塞模型中心请求线程、绝不在 MySQL 事务内做出站；
 * 池拒绝 / 未就绪只日志，不影响平台级设置结果（平台级是配置事实，向量是派生数据）。
 */
@Component
public class MemoryPlatformModelHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryPlatformModelHook.class);

    private final MemoryVectorModelSlotResolver slots;
    private final MemoryVectorSupport support;
    private final MemoryVectorReconciler reconciler;
    private final MemoryVectorRebuildState rebuildState;
    private final ThreadPoolTaskExecutor memoryTaskExecutor;

    /**
     * @param slots              平台级实例解析缓存
     * @param support            就绪快照（ensure 在此发生）
     * @param reconciler         存量补同步（有界一轮）
     * @param rebuildState       受控重建状态（重建期间禁止改平台级）
     * @param memoryTaskExecutor 记忆专用池（有界队列 + Abort），不阻塞请求线程
     */
    public MemoryPlatformModelHook(MemoryVectorModelSlotResolver slots, MemoryVectorSupport support,
                                   MemoryVectorReconciler reconciler, MemoryVectorRebuildState rebuildState,
                                   @Qualifier("memoryTaskExecutor") ThreadPoolTaskExecutor memoryTaskExecutor) {
        this.slots = slots;
        this.support = support;
        this.reconciler = reconciler;
        this.rebuildState = rebuildState;
        this.memoryTaskExecutor = memoryTaskExecutor;
    }

    /**
     * 是否已有补同步在跑。模型中心据此拒绝「同步中切换平台级 Embedding」。
     *
     * @return true 表示同步进行中
     */
    public boolean isSyncRunning() {
        return reconciler.isRunning();
    }

    /**
     * 是否处于「受控重建未完成」状态（含等待全量补同步）。
     * 与 {@link #isSyncRunning()} 一起构成平台级 Embedding 的变更互斥：
     * 重建期间集合可能刚被删建、存量还没补回来，此时再换维会让缺口无法归因。
     *
     * @return true 表示重建进行中
     */
    public boolean isRebuilding() {
        return rebuildState.isRebuilding();
    }

    /**
     * 平台级设置/取消/换绑后调用：立即失效缓存；消息提交后按新配置 ensure，
     * 若本次是「获得可用的 Embedding 平台级」再跑有界一轮补同步。
     *
     * @param capability 发生变更的用途
     * @param resync     true=需要补同步（Embedding 首次设置或换绑）；false=取消锁定/仅 Rerank
     */
    public void onPlatformChanged(ModelCapability capability, boolean resync) {
        // 先失效缓存：即使后续出站被拒，下一次调用也会重新查库，不会继续用旧实例
        slots.invalidate();
        support.invalidate();
        if (capability != ModelCapability.EMBEDDING) {
            // Rerank 不影响 isReady，也不需要补同步；失效后下次访问自然重算
            return;
        }
        dispatchAfterCommit(() -> refreshAndResync(resync));
    }

    /**
     * 连通测试写回维度快照后调用：只失效缓存，不触发补同步（实例参数未变）。
     *
     * @param capability 被测用途；非 EMBEDDING 只失效解析缓存
     */
    public void onSnapshotUpdated(ModelCapability capability) {
        slots.invalidate();
        if (capability == ModelCapability.EMBEDDING) {
            support.invalidate();
        }
    }

    /**
     * 池线程任务：按新平台级快照 ensure；就绪且需要补同步时才跑有界一轮。
     *
     * @param resync 是否允许补同步
     */
    private void refreshAndResync(boolean resync) {
        MemoryVectorReadiness readiness = support.refresh();
        if (!resync) {
            return;
        }
        if (!readiness.ready()) {
            // 未就绪（无快照维 / ensure 失败 / 维冲突）时绝不投递：补同步会在 not_ready 直接返回
            log.warn("[MEMORY] 平台级 Embedding 已变更但向量未就绪，跳过补同步 reason={}", readiness.reason());
            return;
        }
        try {
            MemoryVectorReconciler.Summary summary = reconciler.runOnce();
            // 本轮是有界的：剩下多少要如实交接给自动续补，否则刷新页面后只能人工点
            int pending = reconciler.pendingCount();
            if (pending > 0) {
                rebuildState.requestDrain();
            } else {
                // 已补齐：顺手撤掉可能残留的续补请求，避免页头一直显示「进行中」
                rebuildState.clearIfDrained(pending);
            }
            log.info("[MEMORY] 平台级 Embedding 变更后补同步 reason={} scanned={} synced={} failed={} pendingAfter={}",
                    summary.reason(), summary.scanned(), summary.synced(), summary.failed(), pending);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 平台级 Embedding 变更后补同步异常 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 事务内注册 afterCommit，事务外立即投递；出站绝不挂在 MySQL 事务上。
     *
     * @param task 提交后要执行的记忆侧任务
     */
    private void dispatchAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(task);
                }
            });
            return;
        }
        dispatch(task);
    }

    /**
     * 投递到记忆池；拒绝只日志，状态位仍由补同步兜底。
     *
     * @param task 记忆侧任务
     */
    private void dispatch(Runnable task) {
        try {
            memoryTaskExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("[MEMORY] 平台级变更后记忆任务异常 type={} msg={}",
                            e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } catch (TaskRejectedException e) {
            // 降级：本轮不补，状态位仍是 PENDING/FAILED，后续手动 reconcile 会接手
            log.warn("[MEMORY] 平台级变更后记忆任务投递被拒绝 reason=pool_saturated");
        }
    }
}
