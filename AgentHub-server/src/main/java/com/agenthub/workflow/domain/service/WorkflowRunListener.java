package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;

import java.util.List;
import java.util.Map;

/**
 * 工作流节点状态迁移的观察口。所属域：workflow 的 domain。
 *
 * <p>为什么需要它：工作台把一次工作流运行展示成一张带节点时间轴的执行卡片，
 * 只靠「开始 / 结束」两条状态句无法画出「谁在跑、谁已完成」。引擎在每次节点状态
 * 真正落库之后回调这里，调用方（runtime 的适配层）据此推送卡片快照。
 *
 * <p>边界：
 * <ul>
 *   <li><b>只报告已发生的事实</b>：回调发生在 {@code workflow_node_run} 更新之后，
 *       不是预测；没有回调就没有进度，调用方不得自行编造；</li>
 *   <li><b>旁路</b>：实现抛异常不得影响调度，引擎会吞掉并只记日志；</li>
 *   <li><b>线程</b>：在同一次同步执行线程上回调（FORK 分支也是顺序执行），
 *       实现不应做长耗时操作，也不要反向调用引擎；</li>
 *   <li>节点输入输出可能含业务数据，回调里不要把它们带出去，只报状态与编码。</li>
 * </ul>
 */
public interface WorkflowRunListener {

    /**
     * 调度开始：一次性给出本次 run 的全部节点与它们的当前行（含已存在的状态）。
     *
     * <p>用于卡片先按图顺序画出「未开始」的时间轴，再由 {@link #nodeChanged} 逐个点亮。
     * START / END 也在列表里，由展示层按类型剔除（它们不是执行步骤）。
     *
     * @param nodes 图内节点，按图的顺序
     * @param rows  与 {@code nodes} 对应的运行态行，顺序一致
     */
    void runStarted(List<WorkflowNode> nodes, List<WorkflowNodeRun> rows);

    /**
     * 调度开始（带图结构）：在「全部节点与当前行」之外，再给出节点 id → 出边。
     *
     * <p>为什么需要：工作台把循环体圈成一个框，框的范围由图上 {@code LOOP_BODY} /
     * {@code LOOP_BACK} 边决定，只靠节点列表算不出来。这里只多给一份只读索引，
     * <b>不改调度、不加事件</b>；默认实现退回不带图结构的那条，旧实现无需改动。
     *
     * @param nodes     图内节点，按图的顺序
     * @param rows      与 {@code nodes} 对应的运行态行（含历史轮次），顺序一致
     * @param outgoing  节点 id → 出边（保持图内顺序）；只读
     */
    default void runStarted(List<WorkflowNode> nodes, List<WorkflowNodeRun> rows,
                            Map<String, List<WorkflowEdge>> outgoing) {
        runStarted(nodes, rows);
    }

    /**
     * 某个节点状态发生变化（已落库）。
     *
     * @param node 节点定义（取展示名 / 编码 / 类型）
     * @param row  更新后的运行态（取状态 / 错误摘要）
     */
    void nodeChanged(WorkflowNode node, WorkflowNodeRun row);

    /**
     * 运行时限快照（整段上限 / 当前节点上限 / 各自的截止时刻）。
     *
     * <p>与节点状态分开上报：时限不是节点事实，但卡片要把它和节点一起画。
     * 默认空实现，避免旧实现（测试替身等）被迫改代码。
     *
     * @param limits 时限快照；可空（调用方仅在算得出时上报）
     */
    default void limits(WorkflowCardLimits limits) {
        // 缺省什么也不做：卡片可以不展示时限
    }
}
