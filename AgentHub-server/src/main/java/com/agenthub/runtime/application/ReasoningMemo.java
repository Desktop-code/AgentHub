package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.service.TrialProgress;

/**
 * 判定 / 汇总推理的旁路抄写器。所属域：runtime（应用层）。
 *
 * <p>为什么需要它：判路由与汇总都是一次独立的模型调用，它们的推理只经
 * {@link TrialProgress#reasoning(String)} 流式下发，不进入子 Agent 的 traces。
 * 于是刷新后入口 / 方案 / 汇总的思考框就没了。这里在出站的同时把同一段推理抄一份，
 * 交给时间线写入器落库。
 *
 * <p>边界：
 * <ul>
 *   <li>只抄思考，不抄正文 / 工具：那两类已有自己的落库路径，重复写会出现重影；</li>
 *   <li>纯透传：不改变任何事件的顺序、次数与内容，落库失败也不影响出站；</li>
 *   <li>非线程安全：每次模型调用各建一个，由调用线程独占使用。</li>
 * </ul>
 */
final class ReasoningMemo implements TrialProgress {

    private final TrialProgress delegate;
    private final StringBuilder sink;

    /**
     * @param delegate 真实出站；非空
     * @param sink     推理抄写目标；非空
     */
    ReasoningMemo(TrialProgress delegate, StringBuilder sink) {
        this.delegate = delegate;
        this.sink = sink;
    }

    /**
     * @return 抄到的推理文本；没有推理时为空串
     */
    String text() {
        return sink.toString();
    }

    @Override
    public void phase(String phase) {
        delegate.phase(phase);
    }

    @Override
    public void route(String summary) {
        delegate.route(summary);
    }

    @Override
    public void reasoning(String delta) {
        append(delta);
        delegate.reasoning(delta);
    }

    @Override
    public void reasoningPinned(String delta) {
        append(delta);
        delegate.reasoningPinned(delta);
    }

    @Override
    public void beginParticipant(ParticipantView participant) {
        delegate.beginParticipant(participant);
    }

    @Override
    public void attributeParticipant(String participantId) {
        delegate.attributeParticipant(participantId);
    }

    @Override
    public void toolStart(String name) {
        delegate.toolStart(name);
    }

    @Override
    public void tool(String name, String arguments, String result, boolean success) {
        delegate.tool(name, arguments, result, success);
    }

    @Override
    public void assistant(String delta) {
        delegate.assistant(delta);
    }

    @Override
    public void deliverParticipantConclusion(String routeLine, String text) {
        delegate.deliverParticipantConclusion(routeLine, text);
    }

    @Override
    public TrialProgress conclusionChannel() {
        return delegate.conclusionChannel();
    }

    @Override
    public void file(GeneratedFile file) {
        delegate.file(file);
    }

    @Override
    public void choice(AgentChoice choice) {
        delegate.choice(choice);
    }

    @Override
    public void childPlanPending(ChildPlanPendingView pending) {
        delegate.childPlanPending(pending);
    }

    @Override
    public void vision(String text) {
        delegate.vision(text);
    }

    @Override
    public void plan(PlanView plan) {
        delegate.plan(plan);
    }

    @Override
    public void step(StepView step) {
        delegate.step(step);
    }

    /**
     * 抄一段推理。空白增量不抄：它们不会产生可见思考框，落库只会多一条空记录。
     *
     * @param delta 推理增量
     */
    private void append(String delta) {
        if (delta != null && !delta.isEmpty()) {
            sink.append(delta);
        }
    }
}
