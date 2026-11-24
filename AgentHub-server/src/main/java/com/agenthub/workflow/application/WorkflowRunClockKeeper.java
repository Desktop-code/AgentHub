package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowRunLimits;
import com.agenthub.workflow.infra.WorkflowProperties;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作流单次调度的时限账本。所属域：workflow 的 application。
 *
 * <p>从 {@code WorkflowEngine} 搬出：整段上限 / 截止 / 单节点上限 / 是否在等待，
 * 以及「截止首次调度落库、等待时长补回截止」的读写口径。
 *
 * <p>必须保持不变的口径：
 * <ul>
 *   <li>上限 = min(工作流自身配置, 平台硬顶)；run 上已落过的值优先，再与当前平台硬顶取小；</li>
 *   <li>截止时刻首次调度时落库，之后每次只读库里的那一份，刷新 / 回放看到同一个截止；</li>
 *   <li>从 WAITING_REPLY / PAUSED_HUMAN 恢复时把等待时长补回截止并清空 waiting_since，
 *       同一段等待只补一次；</li>
 *   <li>账本按线程存放，一次 {@code execute} 一份，由引擎在 finally 清理。</li>
 * </ul>
 */
public class WorkflowRunClockKeeper {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunClockKeeper.class);

    /** run 仓储：落库 / 读回上限与截止。 */
    private final WorkflowRunRepository runs;

    /** 工作流配置：平台硬顶。 */
    private final WorkflowProperties properties;

    /**
     * 当前执行线程上的时限账本。
     *
     * <p>为什么用 ThreadLocal 而不是把参数一路传到底：一次 {@code execute} 会穿过
     * 十来个私有方法，且引擎本来就是同步顺序执行（FORK 也不并发），
     * 一次调用内同线程唯一；清理放在 {@code execute} 的 finally。
     */
    private final ThreadLocal<RunClock> holder = new ThreadLocal<>();

    /**
     * @param runs       run 仓储
     * @param properties 工作流配置
     */
    public WorkflowRunClockKeeper(WorkflowRunRepository runs, WorkflowProperties properties) {
        this.runs = runs;
        this.properties = properties;
    }

    /**
     * 把本轮账本挂到当前线程。
     *
     * @param clock 已解析的账本
     */
    public void bind(RunClock clock) {
        holder.set(clock);
    }

    /**
     * @return 当前线程账本；未进入调度时为空
     */
    public RunClock clock() {
        return holder.get();
    }

    /**
     * 清理当前线程账本：池线程不携带上一轮的上限与截止。
     */
    public void clear() {
        holder.remove();
    }

    /**
     * @return 平台硬顶（毫秒），来自 {@code agenthub.workflow.wall-ms}
     */
    public long platformWallMs() {
        return properties.effectiveWallMs();
    }

    /**
     * 建本轮时限账本：上限 = min(工作流配置, 平台硬顶)，截止时刻首次调度时落库。
     *
     * <p>为什么截止要落库而不是每次调度现算：卡片要能刷新、离开再进、回放。
     * 只有「同一个截止」才能让这些场景看到的剩余时间一致，而不是每帧重头计时。
     *
     * <p>等待不计入：从 WAITING_REPLY / PAUSED_HUMAN 恢复时，把 {@code waiting_since}
     * 到现在的时长补回截止，并清空 {@code waiting_since}——
     * 这样同一段等待只补一次，等待期间也不消耗整段上限与平台硬顶。
     *
     * @param run        已落库的 run（状态已是 RUNNING）
     * @param graph      本轮图快照（取工作流自身配置的两项上限）
     * @param platformMs 平台硬顶（毫秒）
     * @param operatorId 操作人
     * @return 本轮账本
     */
    public RunClock open(WorkflowRun run, WorkflowGraph graph, long platformMs, long operatorId) {
        WorkflowRunLimits resolved = resolveRunLimits(run, graph, platformMs);
        long runLimitMs = resolved.runLimitMs();
        long nodeLimitMs = resolved.nodeLimitMs();
        long nowMs = System.currentTimeMillis();
        Long waitingSinceMs = run.getWaitingSince() == null ? null : toEpochMs(run.getWaitingSince());
        // 恢复调度：先把等待时长补回截止，再决定落库值
        long deadlineMs;
        if (run.getRunDeadline() == null) {
            // 首次调度：从现在起算整段上限
            deadlineMs = nowMs + runLimitMs;
        } else {
            deadlineMs = toEpochMs(run.getRunDeadline());
        }
        if (waitingSinceMs != null) {
            long pausedMs = Math.max(0L, nowMs - waitingSinceMs);
            deadlineMs += pausedMs;
            log.info("工作流恢复调度，等待时长不计入运行时限 runId={} pausedMs={} runLimitMs={}",
                    run.getId(), pausedMs, runLimitMs);
        }
        boolean needsPersist = run.getRunDeadline() == null
                || waitingSinceMs != null
                || run.getRunLimitMs() == null
                || run.getNodeLimitMs() == null;
        if (needsPersist) {
            LocalDateTime deadlineAt = toLocalDateTime(deadlineMs);
            /*
             * 落库失败不阻断调度：本次仍按内存账本执行（最坏是重启后按图重新解析一次上限）。
             * 但 waiting_since 必须一并清空，否则同一段等待会在下一次恢复时被重复补回。
             */
            runs.updateRunLimits(run.getId(), runLimitMs, nodeLimitMs, deadlineAt, null, operatorId);
            run.setRunLimitMs(runLimitMs);
            run.setNodeLimitMs(nodeLimitMs);
            run.setRunDeadline(deadlineAt);
            run.setWaitingSince(null);
        }
        return new RunClock(runLimitMs, deadlineMs, nodeLimitMs, null);
    }

    /**
     * 解析本轮两项上限。
     *
     * <p>run 上已落过的值优先（重启后仍按最初那一刻的上限执行），再与当前平台硬顶取小：
     * 运维把 yml 调小后，在途 run 也必须立刻服从新的硬顶。
     *
     * @param run        已落库的 run
     * @param graph      本轮图快照
     * @param platformMs 平台硬顶（毫秒）
     * @return 生效的整段 / 单节点上限
     */
    private WorkflowRunLimits resolveRunLimits(WorkflowRun run, WorkflowGraph graph, long platformMs) {
        WorkflowRunLimits fromGraph;
        try {
            fromGraph = WorkflowRunLimits.resolve(graph, platformMs);
        } catch (RuntimeException ex) {
            // 图 JSON 坏掉不能把 run 直接打死：按平台硬顶跑，问题留给校验器
            log.warn("解析工作流时限失败，按平台硬顶执行 runId={} type={}",
                    run.getId(), ex.getClass().getSimpleName());
            fromGraph = WorkflowRunLimits.resolve(null, platformMs);
        }
        long runLimitMs = run.getRunLimitMs() != null && run.getRunLimitMs() > 0
                ? Math.min(run.getRunLimitMs(), platformMs)
                : fromGraph.runLimitMs();
        long nodeLimitMs = run.getNodeLimitMs() != null && run.getNodeLimitMs() > 0
                ? Math.min(run.getNodeLimitMs(), runLimitMs)
                : fromGraph.nodeLimitMs();
        return WorkflowRunLimits.of(runLimitMs, nodeLimitMs);
    }

    /**
     * 进入等待态：记下「等待开始时刻」，供卡片冻结剩余时间。
     *
     * @param waitingAt 等待开始时刻
     */
    public void markWaiting(LocalDateTime waitingAt) {
        RunClock clock = holder.get();
        if (clock != null && waitingAt != null) {
            clock.waitingAtMs = toEpochMs(waitingAt);
        }
    }

    /**
     * 该节点是否已超过单节点上限。未配置上限 / 节点没有开始时间时恒 false。
     *
     * @param row 运行态行
     * @return true 表示这一步已超时
     */
    public boolean nodeTimedOut(WorkflowNodeRun row) {
        RunClock clock = holder.get();
        if (clock == null || clock.nodeLimitMs <= 0 || row == null || row.getStartTime() == null) {
            return false;
        }
        return System.currentTimeMillis() > toEpochMs(row.getStartTime()) + clock.nodeLimitMs;
    }

    /**
     * @param row 节点运行态行
     * @return 已耗时毫秒；没有开始时间时为 0
     */
    public long costMs(WorkflowNodeRun row) {
        return row.getStartTime() == null ? 0L
                : Math.max(0L, System.currentTimeMillis() - toEpochMs(row.getStartTime()));
    }

    /** epoch 毫秒 → 服务器时区 LocalDateTime（落库列类型是 DATETIME） */
    public static LocalDateTime toLocalDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    /** LocalDateTime（服务器时区）→ epoch 毫秒 */
    public static long toEpochMs(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 单次调度的时限账本（线程内）。所属域：workflow。
     *
     * @param runLimitMs  整段上限（毫秒）
     * @param deadlineMs  整段截止（epoch 毫秒）
     * @param nodeLimitMs 单节点上限（毫秒）；<=0 表示未配置
     * @param waitingAtMs 进入等待的时刻（epoch 毫秒）；null 表示正在跑
     */
    public static final class RunClock {
        public final long runLimitMs;
        public final long deadlineMs;
        public final long nodeLimitMs;
        public Long waitingAtMs;

        private RunClock(long runLimitMs, long deadlineMs, long nodeLimitMs, Long waitingAtMs) {
            this.runLimitMs = runLimitMs;
            this.deadlineMs = deadlineMs;
            this.nodeLimitMs = nodeLimitMs;
            this.waitingAtMs = waitingAtMs;
        }
    }
}
