package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.service.WorkflowCardLimits;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作流卡片观察者发布。所属域：workflow 的 application。
 *
 * <p>从 {@code WorkflowEngine} 搬出：把「全部节点已铺开」「某节点状态迁移」「时限快照」
 * 交给本次调度的观察者。不落库、不判断业务结果。
 *
 * <p>必须保持不变的口径：
 * <ul>
 *   <li>只在节点状态**落库之后**发布（由引擎保证调用时机）；</li>
 *   <li>{@code node} 先发时限、再发节点状态，卡片一次画全「节点 + 上限 + 剩余」；</li>
 *   <li>观察者是旁路：任何异常只记日志、吞掉，绝不改变 run 的结果；</li>
 *   <li>观察者按线程存放，一次调用一份，由引擎在 finally 清理。</li>
 * </ul>
 */
public class WorkflowCardPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCardPublisher.class);

    /** 时限账本：节点级剩余时间从这里取。 */
    private final WorkflowRunClockKeeper clockKeeper;

    /**
     * 当前执行线程上的节点观察者。
     *
     * <p>为什么用 ThreadLocal 而不是把参数一路传到底：一次 {@code execute} 会穿过
     * 十来个私有方法，逐个加参数只会把「谁在观察」这件事摊进调度代码；
     * 引擎本来就是同步顺序执行，一次调用内同线程唯一。
     */
    private final ThreadLocal<WorkflowRunListener> holder = new ThreadLocal<>();

    /**
     * @param clockKeeper 时限账本
     */
    public WorkflowCardPublisher(WorkflowRunClockKeeper clockKeeper) {
        this.clockKeeper = clockKeeper;
    }

    /**
     * 把观察者挂到当前线程。
     *
     * @param listener 节点状态观察者
     */
    public void bind(WorkflowRunListener listener) {
        holder.set(listener);
    }

    /**
     * 清理当前线程观察者：池线程复用不会把上一位的观察者带给下一位。
     */
    public void clear() {
        holder.remove();
    }

    /**
     * 通知观察者「本次调度的全部节点与当前状态」。
     *
     * @param runtime 图
     * @param rows    与图顺序一致的运行态行
     */
    public void started(WorkflowRuntimeGraph runtime, List<WorkflowNodeRun> rows) {
        WorkflowRunListener listener = holder.get();
        if (listener == null) {
            return;
        }
        try {
            listener.runStarted(runtime.nodes(), rows);
        } catch (RuntimeException ex) {
            // 观察是旁路：画不出卡片不能把一次正常的 run 变成失败
            log.warn("工作流节点观察失败 action=runStarted type={}", ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 通知观察者某节点状态已迁移（在落库之后调用）。
     *
     * @param node 节点定义
     * @param row  已更新的运行态
     */
    public void node(WorkflowNode node, WorkflowNodeRun row) {
        WorkflowRunListener listener = holder.get();
        if (listener == null || node == null || row == null) {
            return;
        }
        /*
         * 顺序有意为之：先把时限（含当前节点截止）交给观察者，再报节点状态。
         * 观察者的 nodeChanged 会立刻推一帧快照，此时账本已是新的，
         * 卡片就能一次画全「节点 + 该节点上限 + 剩余」，不会出现落后一帧的剩余时间。
         */
        limits(node, row);
        try {
            listener.nodeChanged(node, row);
        } catch (RuntimeException ex) {
            log.warn("工作流节点观察失败 action=nodeChanged nodeCode={} type={}",
                    node.getCode(), ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 把时限快照交给观察者（只更新账本，不单独推帧）。
     *
     * <p>观察者是旁路：这里抛异常只记日志，不能影响调度结论。
     *
     * @param node 当前要展示的节点；null 表示不展示节点级时限
     * @param row  该节点的运行态；为空或非 RUNNING 时不展示节点级时限
     */
    public void limits(WorkflowNode node, WorkflowNodeRun row) {
        WorkflowRunListener listener = holder.get();
        WorkflowRunClockKeeper.RunClock clock = clockKeeper.clock();
        if (listener == null || clock == null) {
            return;
        }
        Long nodeLimitMs = null;
        Long nodeDeadlineMs = null;
        String nodeCode = null;
        String nodeName = null;
        // 只有「该节点正在跑 + 配了单节点上限」才给节点行：其余状态画上限没有意义
        if (node != null && row != null && clock.nodeLimitMs > 0
                && WorkflowNodeRunStatus.parse(row.getStatus()) == WorkflowNodeRunStatus.RUNNING
                && row.getStartTime() != null) {
            nodeLimitMs = clock.nodeLimitMs;
            nodeDeadlineMs = WorkflowRunClockKeeper.toEpochMs(row.getStartTime()) + clock.nodeLimitMs;
            nodeCode = node.getCode();
            nodeName = node.getName();
        }
        try {
            listener.limits(new WorkflowCardLimits(clock.runLimitMs, clock.deadlineMs, clock.waitingAtMs,
                    nodeCode, nodeName, nodeLimitMs, nodeDeadlineMs));
        } catch (RuntimeException ex) {
            log.warn("工作流时限观察失败 nodeCode={} type={}",
                    nodeCode, ex.getClass().getSimpleName(), ex);
        }
    }
}
