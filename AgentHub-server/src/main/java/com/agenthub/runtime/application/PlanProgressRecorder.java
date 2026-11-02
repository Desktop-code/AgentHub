package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.PlanJson;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划执行过程的增量流水。所属域：runtime。
 *
 * <p>为什么需要它：{@link TrialProgress} 的增量本来只走 SSE 出站，不落库；
 * 但计划与步骤必须能在刷新后从 agent_run_event 回放出来（结构化的步骤列表不能靠纯文本凑）。
 * 于是在真实出站之外再挂一层：把所有时间线增量按发生顺序记下来，
 * 由调用方在整轮结束（含失败）后一次性写成 PLAN / STEP / REASONING / TOOL 事件。
 *
 * <p>顺序保证：记的是同一线程内的调用顺序，即
 * 「规划思考 → 计划 → 第 1 步开始 → 该步思考/工具 → 第 1 步完成 → …」，
 * 与 seq 落库顺序一致。前端据此把「计划前」的思考归到计划阶段、
 * 「第 N 步开始」与「第 N 步完成」之间的思考归到该步，而不必再靠猜。
 *
 * <p>为什么计划模式不再走 {@code appendTraces}：traces 是整轮结束后的合并快照，
 * 一次性写出来会把所有步骤的推理/工具堆到全部步骤之后，时间线上就出现「先列完全部步骤，再统一思考」。
 * 本类逐条登记才保得住真实交错顺序。
 *
 * <p>助手正文刻意不在此登记：终答仍由调用方在收尾时写一条 assistant 事件，
 * 避免同一段正文被拆成多段碎片事件。
 */
public final class PlanProgressRecorder implements TrialProgress {

    /**
     * 一条待落库的时间线事件。
     *
     * @param type    事件类型（PLAN / STEP / REASONING / TOOL）
     * @param summary 短摘要，进 summary 列
     * @param content 正文，进 content_text
     */
    public record Frame(EventType type, String summary, String content) {
    }

    private final TrialProgress delegate;
    private final List<Frame> frames = new ArrayList<>();

    /**
     * @param delegate 真实出站（SSE + 内存快照），可空；空表示同步路径，只收集不推送
     */
    public PlanProgressRecorder(TrialProgress delegate) {
        this.delegate = delegate;
    }

    /**
     * @return 按发生顺序记录的计划类事件副本
     */
    public synchronized List<Frame> frames() {
        return List.copyOf(frames);
    }

    /**
     * 取走尚未落库的帧并清空。
     *
     * <p>为什么需要「取走」而不是每次读全量：一轮里规划阶段先落一次事件，
     * 自动执行时同一条链路还会继续产生步骤帧。若每次都读全量，规划那几条会被写两遍，
     * 时间线上就会出现两份计划卡。此方法保证每条帧只落一次。
     *
     * @return 自上次取走之后新增的帧；无新增时为空列表
     */
    public synchronized List<Frame> drain() {
        if (frames.isEmpty()) {
            return List.of();
        }
        List<Frame> out = List.copyOf(frames);
        frames.clear();
        return out;
    }

    /** {@inheritDoc} */
    @Override
    public void phase(String phase) {
        if (delegate != null) {
            delegate.phase(phase);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void route(String summary) {
        if (delegate != null) {
            delegate.route(summary);
        }
    }

    /**
     * 思考增量：连续同类型增量合并进上一条帧，避免一个 token 一条事件。
     * {@inheritDoc}
     */
    @Override
    public synchronized void reasoning(String delta) {
        appendDelta(EventType.REASONING, delta);
        if (delegate != null) {
            delegate.reasoning(delta);
        }
    }

    /**
     * 工具结束：一条调用一条帧，首行是调用行，空行后是结果。
     * {@inheritDoc}
     */
    @Override
    public synchronized void tool(String name, String arguments, String result, boolean success) {
        String line = TrialTexts.toolDisplay(name, arguments);
        if (!success) {
            line = line + " · 失败";
        }
        String body = result == null || result.isBlank() ? line : line + "\n\n" + result;
        frames.add(new Frame(EventType.TOOL, line, body));
        if (delegate != null) {
            delegate.tool(name, arguments, result, success);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void assistant(String delta) {
        if (delegate != null) {
            delegate.assistant(delta);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void plan(PlanView plan) {
        if (plan == null) {
            return;
        }
        // 状态更新事件不带 steps，摘要只写状态，避免和「计划共 N 步」重复
        String summary = plan.steps().isEmpty()
                ? "计划" + plan.stateText()
                : plan.summary();
        frames.add(new Frame(EventType.PLAN, summary, PlanJson.planPayload(plan)));
        if (delegate != null) {
            delegate.plan(plan);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void step(StepView step) {
        if (step == null) {
            return;
        }
        // 正文只留结果，摘要给时间线一行；失败原因已由上层带进 text
        frames.add(new Frame(EventType.STEP, step.displayLine(), PlanJson.stepPayload(step)));
        if (delegate != null) {
            delegate.step(step);
        }
    }

    /**
     * 文件产物：既落帧（刷新后回放出下载卡片），也转给真实出站。
     * {@inheritDoc}
     */
    @Override
    public synchronized void file(GeneratedFile file) {
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            return;
        }
        // 摘要给「文件名 + 可读体积」，正文是结构化 JSON：前端要拿 fileName 去拼下载地址
        frames.add(new Frame(EventType.FILE, file.fileName() + "（" + file.humanSize() + "）",
                GeneratedFileMarker.payload(file.fileName(), file.size())));
        if (delegate != null) {
            delegate.file(file);
        }
    }

    /**
     * 追加一段增量：末尾同类型帧就续写，否则新开一帧。
     *
     * @param type  事件类型
     * @param delta 增量正文，空则忽略
     */
    private void appendDelta(EventType type, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        int last = frames.size() - 1;
        if (last >= 0 && frames.get(last).type() == type) {
            Frame previous = frames.get(last);
            String merged = previous.content() + delta;
            frames.set(last, new Frame(type, TrialTexts.summary(merged), merged));
            return;
        }
        frames.add(new Frame(type, TrialTexts.summary(delta), delta));
    }
}
