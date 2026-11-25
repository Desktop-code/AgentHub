package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepState;
import com.agenthub.runtime.domain.model.StepView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划模式的时间线落库顺序。所属域：runtime。
 *
 * <p>这组断言钉死页面的展示顺序：
 * 计划阶段思考 → 计划 → 第 N 步开始 → 该步思考/工具 → 第 N 步完成。
 * 一旦有人把思考改回「整轮结束统一追加」，这里会先红。
 */
class PlanProgressRecorderOrderTest {

    @Test
    void planThinkingComesBeforePlanCard() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);

        // 规划阶段：思考 → 计划
        recorder.reasoning("先拆成两步");
        recorder.plan(new PlanView("p1", PlanState.PENDING, List.of(
                new PlanStep(1, "查天气", ""),
                new PlanStep(2, "给建议", ""))));

        List<PlanProgressRecorder.Frame> frames = recorder.frames();
        assertThat(frames).extracting(PlanProgressRecorder.Frame::type)
                .containsExactly(EventType.REASONING, EventType.PLAN);
        // 思考挂在计划卡之前，页面才有「计划思考」这一折叠区
        assertThat(frames.get(0).content()).isEqualTo("先拆成两步");
        assertThat(frames.get(1).content()).contains("\"planId\":\"p1\"");
    }

    @Test
    void stepThinkingAndToolsStayInsideItsOwnStep() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);

        recorder.plan(new PlanView("p1", PlanState.EXECUTING, List.of(
                new PlanStep(1, "查天气", ""),
                new PlanStep(2, "给建议", ""))));
        recorder.step(new StepView(1, "查天气", StepState.RUNNING, ""));
        recorder.reasoning("要查北京");
        recorder.tool("weather", "{\"city\":\"北京\"}", "晴 25 度", true);
        recorder.step(new StepView(1, "查天气", StepState.DONE, "晴 25 度"));
        recorder.step(new StepView(2, "给建议", StepState.RUNNING, ""));
        recorder.reasoning("按天气给穿衣建议");
        recorder.step(new StepView(2, "给建议", StepState.DONE, "穿长袖"));

        assertThat(recorder.frames()).extracting(PlanProgressRecorder.Frame::type)
                .containsExactly(
                        EventType.PLAN,
                        EventType.STEP,
                        // 第 1 步的思考与工具必须夹在「第 1 步开始」与「第 1 步完成」之间
                        EventType.REASONING, EventType.TOOL, EventType.STEP,
                        EventType.STEP,
                        EventType.REASONING, EventType.STEP);
    }

    @Test
    void consecutiveReasoningMergesIntoOneFrame() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);

        recorder.reasoning("先看");
        recorder.reasoning("一下");
        recorder.reasoning("天气");

        // 一个 token 一条会造成几十条事件；同类型连续增量必须合并
        assertThat(recorder.frames()).hasSize(1);
        assertThat(recorder.frames().get(0).content()).isEqualTo("先看一下天气");
    }

    @Test
    void toolFrameKeepsCallLineAndResult() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);

        recorder.tool("weather", "{\"city\":\"北京\"}", "晴 25 度", true);
        recorder.tool("weather", "{}", "超时", false);

        List<PlanProgressRecorder.Frame> frames = recorder.frames();
        // 失败要在调用行上标出来，与落库/直播口径一致
        assertThat(frames.get(1).summary()).contains("· 失败");
        assertThat(frames.get(0).content()).contains("调用工具 weather").contains("\n\n").contains("晴 25 度");
    }

    @Test
    void assistantDeltasAreNotRecordedAsFrames() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);

        // 终答由调用方收尾时单独写一条 assistant 事件，旁路不能再登记碎片
        recorder.assistant("终答片段");

        assertThat(recorder.frames()).isEmpty();
    }
}
