package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.model.WorkflowRun;

/**
 * 调试试跑（TRIAL）接上工作流卡片直播的挂载口。所属域：workflow 的 domain。
 *
 * <p>为什么单独抽一个接口：调度发生在 {@code workflow} 域，而卡片投影、节点过程范围
 * 都在 {@code runtime} 域。调度侧只需要「开跑前挂上观察者、收口时结算」两件事，
 * 不必依赖 runtime 的实现类；单测也能用空实现走原有路径。
 *
 * <p>边界：
 * <ul>
 *   <li>只挂 {@code trigger_type=TRIAL} 且没有 {@code workbench_session_id} 的运行；
 *       工作台 run 由会话直播负责，这里必须返回 {@code null}；</li>
 *   <li>不新建 run、不写 {@code workflow_run} 之外的会话事件，也不改状态机；</li>
 *   <li>{@link #open} 与 {@link #finish} 在同一个执行线程上成对调用：实现方可能在
 *       {@code open} 时打开线程本地的节点过程范围，并在 {@link #finish} 里关闭；</li>
 *   <li>旁路异常只记日志，绝不能改变调度结果。</li>
 * </ul>
 */
public interface WorkflowTrialLive {

    /**
     * 为一次试跑挂上直播观察者。
     *
     * <p>实现方应按 run 隔离一份卡片投影，并把 {@code reply} 交给投影（续跑时挂到询问节点）。
     * 新建 / 续跑都走这里；同一 run 的后续段落允许复用上一次已发布的卡片作为续跑正文种子。
     *
     * @param run   已落库、准备调度的运行实例
     * @param reply 用户对等待中询问的回复；新建或人工通过时为空
     * @return 交给引擎的节点观察者；不接直播时返回 {@code null}
     */
    WorkflowRunListener open(WorkflowRun run, String reply);

    /**
     * 结算一次试跑的直播。
     *
     * <p>必须在执行线程的 {@code finally} 里调用（与 {@link #open} 配对），
     * 把最后一份卡片按库里的终态收口，并释放线程本地范围。没有对应投影时允许安全空跑。
     *
     * @param runId 运行主键
     */
    void finish(Long runId);
}
