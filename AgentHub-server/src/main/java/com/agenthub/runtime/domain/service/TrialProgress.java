package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepView;

/**
 * 试跑过程中的增量回调。所属域：runtime。
 * 实现方在出站前脱敏。引擎没有给出的思考不要在回调里补写。
 *
 * <p>plan / step 是计划执行模式专属的两类结构化的增量，用 {@code default} 空实现，
 * 让 REACT / ROUTER 的既有包装器（如路由分块、静音助手）不必逐个补齐方法签名。
 */
public interface TrialProgress {

    /**
     * @param phase planning / reasoning / tool / answering
     */
    void phase(String phase);

    /**
     * Router 父判定结果。前端收进「思考过程」时间线，与工具步同级。
     *
     * @param summary 路由摘要（如「路由到 xxx，依据：…」），不含密钥
     */
    void route(String summary);

    /**
     * @param delta 思考片段，来自 ThinkingBlock
     */
    void reasoning(String delta);

    /**
     * 一次工具结束。参数和结果都还可能含密钥，展示前必须再脱敏。
     *
     * @param name      工具名
     * @param arguments 入参，可能已脱敏
     * @param result    工具结果
     * @param success   引擎状态为成功
     */
    void tool(String name, String arguments, String result, boolean success);

    /**
     * @param delta 助手正文片段
     */
    void assistant(String delta);

    /**
     * 某个参与者的整段成文结论（不是 token 增量）。
     *
     * <p>默认空操作：正文已经按 token 流式推过的路径不能再整段补一次，否则会重影。
     * 只有「静音子正文、避免并行交错」的包装器覆写它，在该参与者结束时按路由行钉回这一段结论。
     *
     * @param routeLine 该参与者的路由行（「路由到 …」）；可空
     * @param text      成文结论；空则忽略
     */
    default void deliverParticipantConclusion(String routeLine, String text) {
        // 流式路径已推过正文
    }

    /**
     * 给「再跑一层方案」用的进度出口：静音包装器返回被包装的下游，
     * 这样内层方案的子结论和汇总结论不会被外层静音吞掉。
     *
     * @return 能真正写出正文的进度；默认就是自己
     */
    default TrialProgress conclusionChannel() {
        return this;
    }

    /**
     * 图片文字提取结果。在主模型开跑之前下发一次，整段文本而非增量。
     *
     * <p>为什么要单独一个事件而不是塞进 reasoning：识别结果不是模型的思考，
     * 它是本轮的事实素材；混进思考块会让用户误以为是主模型自己「读」出来的。
     *
     * @param text 逐图汇总的识别文本，已脱敏
     */
    default void vision(String text) {
        // 不启用图片能力的实现可以不关心
    }

    /**
     * 计划生成或状态变化。前端据此渲染结构化的步骤列表，而不是读一段纯文本。
     * 同一 planId 的多次回调表示状态推进，应原位更新同一张计划卡。
     *
     * @param plan 计划快照；状态更新时 steps 可能为空表示沿用已有步骤
     */
    default void plan(PlanView plan) {
        // REACT / ROUTER 不产生计划，默认忽略
    }

    /**
     * 计划中单步的开始 / 重试 / 完成 / 失败。
     *
     * @param step 单步快照，含该步正文或失败原因
     */
    default void step(StepView step) {
        // REACT / ROUTER 不产生计划步骤，默认忽略
    }

    /**
     * 本轮有文件落进工作区，可以下载了。
     *
     * <p>为什么要单独一个回调而不是靠 {@link #tool(String, String, String, boolean)} 的正文：
     * 工具正文在出站前会被脱敏与截断，产物元数据必须原样到达前端；
     * 而且它要落成独立事件，刷新后才能回放出下载卡片。
     *
     * @param file 产物名与体积；同名文件重复生成时由前端按文件名去重覆盖
     */
    default void file(GeneratedFile file) {
        // 不产生文件的实现（大多数工具）默认忽略
    }

    /**
     * 编排根下某个 P&E 子进入「计划待批准」。
     *
     * <p>与单 Agent 的 {@link #plan(PlanView)} 分开：根侧的批准按钮要按 route_key 定位到具体子，
     * 而单 Agent 计划卡只按 planId 更新；混用会让前端把子计划误当成根计划。
     *
     * @param pending 子计划待批视图（routeKey / 子标识 / 步骤 / 根暂停态）
     */
    default void childPlanPending(ChildPlanPendingView pending) {
        // REACT / 单 Agent 路径不会产生子计划待批，默认忽略
    }
}
