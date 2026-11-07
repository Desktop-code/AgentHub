package com.agenthub.memory.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆向量「受控重建 / 待补同步」的状态机。所属域：memory 的 application。
 * <p>
 * 三个标记刻意分开，因为它们约束的对象不同：
 * <ul>
 *   <li>{@code running}：<b>单次执行</b>互斥。只允许一个重建动作在跑，防止同一集合被并发删建；</li>
 *   <li>{@code rebuilding}：<b>业务状态</b>互斥。从开始 DROP 起一直保持到「全量 PENDING 补同步完成」，
 *       期间禁止设置 / 换绑 / 取消平台级 Embedding（换维与在途重建会互相踩踏）；</li>
 *   <li>{@code autoDrainWanted}：<b>续补请求</b>。平台级换绑不删集合、也不该阻止继续改配置，
 *       但同样会留下 PENDING 存量；置位后由 drain 任务继续推进，补空即自动撤掉。</li>
 * </ul>
 * 为什么重建标记要持续到补同步结束：集合被删建后向量库是空的，此时 ready 可能为 true（集合存在、维度正确），
 * 但存量还没补回来。若这时允许再换绑，会出现「换维 → 又一批 PENDING → 上一轮还没补完」的叠加，
 * 排查时无法区分是哪一次换绑造成的缺口。
 * <p>
 * 边界：标记只在进程内（单实例 jar 部署）。重启会清空标记，但 requeue 已把行落成 PENDING，
 * 补同步仍会接手，不会丢数据；此时页头会以「待补同步未完成」提示并提供手动一轮作为兜底。
 * 多实例跨节点互斥后置为数据库锁。
 */
@Component
public class MemoryVectorRebuildState {

    /** 业务态：重建开始直到全量 PENDING 补完 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);
    /** 执行态：只允许一个重建动作在跑 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 重建开始时刻（毫秒），0 表示未在重建；仅用于日志与排障 */
    private volatile long startedAtMs;
    /**
     * 「平台级变更后待补同步」的自动收敛请求。
     * <p>
     * 与 {@code rebuilding} 刻意分开：同维换绑不删集合、不需要阻止继续变更平台级，
     * 但它同样会留下 PENDING 存量，若只靠人工点「继续补同步」，刷新页面后观感就是「任务断了」。
     * 置位后由 {@code MemoryVectorRebuildDrainJob} 继续推进，补空即自动清除。
     */
    private final AtomicBoolean autoDrainWanted = new AtomicBoolean(false);

    /**
     * 是否处于「重建未完成」状态。模型中心据此拒绝平台级 Embedding 的任何变更。
     *
     * @return true 表示重建进行中（含等待补同步）
     */
    public boolean isRebuilding() {
        return rebuilding.get();
    }

    /**
     * 请求自动续补：由平台级 Embedding 变更的挂载点在「本轮补完仍有剩余」时调用。
     * 不置 {@code rebuilding}，因此不会阻止操作者继续调整平台级配置。
     */
    public void requestDrain() {
        autoDrainWanted.set(true);
    }

    /**
     * 是否需要服务端继续补同步：受控重建未完成，或平台级变更留下了待补存量。
     *
     * @return true 表示还需要一轮接一轮地推进
     */
    public boolean isDrainWanted() {
        return rebuilding.get() || autoDrainWanted.get();
    }

    /**
     * 是否已有一次重建动作在执行（更窄的锁，只挡并发执行）。
     *
     * @return true 表示正在 DROP / ensure / requeue 中
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 重建开始时刻。
     *
     * @return 毫秒时间戳；0 表示未在重建
     */
    public long startedAtMs() {
        return startedAtMs;
    }

    /**
     * 尝试进入执行态。CAS 失败表示已有重建在跑，调用方应立即拒绝而不是排队。
     *
     * @return true 表示本次抢到执行权
     */
    public boolean tryBegin() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        rebuilding.set(true);
        startedAtMs = System.currentTimeMillis();
        return true;
    }

    /**
     * 结束本次执行（无论成功失败）。<b>不清</b> {@code rebuilding}：
     * 是否算「重建完成」由 {@link #clearIfDrained(int)} 按待补同步条数判定。
     */
    public void endRun() {
        running.set(false);
    }

    /**
     * 重建中途失败且集合未被破坏时回滚状态，避免无谓地锁住平台级槽位。
     * 只在「DROP 未执行 / 未真正动数据」的失败路径调用。
     */
    public void abort() {
        running.set(false);
        rebuilding.set(false);
        startedAtMs = 0L;
    }

    /**
     * 待补同步已清空时收口：清掉「平台级变更续补」请求，并在受控重建未完成时放开平台级变更锁。
     * 每次补同步轮次结束后调用（手动接口 / 定时 / 自动续补 / 平台变更挂钩）。
     *
     * @param pending 当前待补同步条数；&lt;=0 表示全部补齐
     * @return true 表示本次调用把「受控重建未完成」状态清掉了
     */
    public boolean clearIfDrained(int pending) {
        // 只认「确切为 0」：pendingCount 读失败时返回 -1，必须保持状态而不是误放开
        if (pending != 0) {
            return false;
        }
        // 平台级变更留下的续补请求：补空即撤，避免下次进入页面仍显示「进行中」
        autoDrainWanted.set(false);
        if (!rebuilding.get()) {
            return false;
        }
        // 只在仍持有执行权时收口：执行中的轮次自己会在结束时清 running，避免提前放开
        rebuilding.set(false);
        startedAtMs = 0L;
        return true;
    }
}
