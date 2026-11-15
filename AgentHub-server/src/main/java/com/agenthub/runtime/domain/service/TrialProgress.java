package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.model.WorkflowRunView;

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
     * 归属「刚推出去的那条 route」的思考增量：不再重推泳道标题。
     *
     * <p>为什么单独一条：并行 / 嵌套执行时，包装器会在每个增量前重推本子「路由到」，
     * 好让前端把交错到达的增量切回正确分块。但汇总这类记录是**先推标记（「方案结论：X」）
     * 再推思考**的：若思考还走重推泳道路由的那条通道，泳道路由会被重新推一次，
     * 前端就会把汇总记录关掉、把这段思考算回方案分流块（分流块因此「重新进入思考」）。
     *
     * <p>默认实现就是 {@link #reasoning(String)}；只有会重推泳道的包装器需要覆写成
     * 「透传但不重推」，其余实现（出站写入、内存快照）不必关心。
     *
     * @param delta 思考片段，来自 ThinkingBlock
     */
    default void reasoningPinned(String delta) {
        reasoning(delta);
    }

    /**
     * 某个参与者**真正开始**这一步：先于该步的任何思考 / 工具 / 正文推出。
     *
     * <p>为什么必须单独一条：前端旧实现要等「有内容」才画块，于是子专家的 route 与第一个
     * 思考字同一毫秒到达时，块仍可能因为空壳被丢掉。这条事件让前端在开跑瞬间就把块画出来，
     * 后面的增量再往里填。
     *
     * <p>幂等：同一参与者只推一次；重复调用由实现自行去重。
     *
     * @param participant 参与者身份（入口 / 方案 / 专家 / 工作流 / 汇总）
     */
    default void beginParticipant(ParticipantView participant) {
        // 不展示分块的实现（如落库回放）不必关心
    }

    /**
     * 后续增量归属哪个参与者。并行 / 嵌套执行时，增量交错到达，
     * 每次切换都要先声明归属，前端才能把这一段思考 / 工具挂到正确的人名下。
     *
     * <p>与 {@link #route(String)} 的区别：route 是「分给谁」的可见文案，会进时间线；
     * 这条只是身份标记，**不产生任何用户可见文字**，因此可以在每个增量前调用，
     * 由实现按「与上一条相同则跳过」去重，不会像旧实现那样每帧重推一句路由。
     *
     * @param participantId 参与者标识，对应 {@link #beginParticipant(ParticipantView)} 的 id
     */
    default void attributeParticipant(String participantId) {
        // 不需要分块的实现默认忽略
    }

    /**
     * 一次工具**开始**执行。与 {@link #tool(String, String, String, boolean)} 成对：
     * 这条用于把「正在调用」显示成胶囊，那条用于把胶囊收成成功 / 失败态。
     *
     * <p>为什么需要单独一条：旧 {@code tool} 事件的语义是「工具已执行完 + 结果」，
     * 工具跑的过程中前端没有任何信号，只能干等——演示稿里那条蓝色「正在调用 xxx…」
     * 就是这个缺口。默认空实现：不关心进度的实现（如落库回放）不必补齐。
     *
     * @param name 工具名
     */
    default void toolStart(String name) {
        // 默认忽略：不展示进度的实现不需要
    }

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
     * 本轮需要用户拍板的结构化选项。前端据此渲染 chips，并从正文里不显示任何 JSON。
     *
     * <p>为什么是整份结构而不是增量：选项是一次成型的短数据，没有流式意义；
     * 它总是晚于终答正文到达，落库事件（{@code CHOICE}）与实时帧共用同一份字段。
     *
     * @param choice 已校验的选项；空值时调用方不应回调
     */
    default void choice(AgentChoice choice) {
        // 不展示选项的实现（调试试跑 / 计划执行）默认忽略
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

    /**
     * 工作流目标的执行卡片快照（整份，不是增量）。
     *
     * <p>为什么是整份：工作流节点数少、状态变化快，逐节点增量还要前端自己合并；
     * 每次给一份完整视图，前端按工作流名原地覆盖即可，刷新与回放也用同一份结构。
     *
     * <p>只允许来自真实运行事件：引擎每迁移一个节点状态推一次，**禁止**在前端或适配层
     * 估算进度。没有事件时宁可不发，也不编造「正在检索知识库…」这类话术。
     *
     * @param view 执行卡片视图；空值忽略
     */
    default void workflow(WorkflowRunView view) {
        // 不产生工作流目标的实现（REACT / 方案内部）默认忽略
    }
}
