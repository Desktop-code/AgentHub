package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;

/**
 * 工作流运行的埋点端口。所属域：workflow 的 domain。
 *
 * <p>调用方：{@code WorkflowEngine}（开跑 / 每节点 / 收尾）与人工驳回、取消等直接在
 * 应用层把 run 推到终态的用例。实现方：{@code com.agenthub.workflow.infra.WorkflowObsTraceWriter}，
 * 它复用 obs 域既有的 {@code TraceRecorder}，不新开第二套写入器。
 *
 * <p>约定：实现必须吞掉自身异常并只打日志——埋点失败绝不能让工作流主跑失败。
 * <p>线程：一次 run 的调度在单线程内同步完成；实现内部按 runId 保存范围，跨线程只做读写，
 * 不承担事务语义。
 */
public interface WorkflowTraceWriter {

    /**
     * 开跑 / 续跑前准备根 trace：首次运行新建并回写 {@code workflow_run.trace_id}；
     * 已有 trace_id（人工通过后恢复调度）则续用同一条 trace。
     *
     * @param run 已落库的 run（需带 id / workflowId / releaseId / triggeredBy）
     */
    void beginRun(WorkflowRun run);

    /**
     * 节点开始：在根 trace 下开一个 TASK 步，用于承载该节点的耗时与入出参摘要。
     *
     * @param run  run
     * @param node 画布节点
     */
    void beginNode(WorkflowRun run, WorkflowNode node);

    /**
     * 节点结束：按 node_run 的终态收口步骤；WAITING_HUMAN 保持 RUNNING，交由根 trace 收尾。
     *
     * @param run  run
     * @param node 画布节点
     * @param row  该节点的运行态（含入参 / 产出 / 状态 / 失败摘要）
     */
    void endNode(WorkflowRun run, WorkflowNode node, WorkflowNodeRun row);

    /**
     * run 进入终态时收口根 trace：SUCCESS→OK、FAILED→ERROR、CANCELLED→CANCELLED。
     * 非终态（RUNNING / PAUSED_HUMAN）不动作，人工卡点保持 RUNNING。
     *
     * @param run run（状态以对象上的最新值为准）
     */
    void finishRun(WorkflowRun run);
}
