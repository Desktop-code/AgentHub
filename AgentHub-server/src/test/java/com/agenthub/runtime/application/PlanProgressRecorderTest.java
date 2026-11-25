package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepState;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划增量旁路：既要转发给出站，又要按发生顺序落库，且每帧只落一次。
 */
class PlanProgressRecorderTest {

    /** 只记录调用顺序的出站替身。 */
    private static final class Spy implements TrialProgress {
        final List<String> calls = new ArrayList<>();

        @Override
        public void phase(String phase) {
            calls.add("phase:" + phase);
        }

        @Override
        public void route(String summary) {
            calls.add("route");
        }

        @Override
        public void reasoning(String delta) {
            calls.add("reasoning");
        }

        @Override
        public void tool(String name, String arguments, String result, boolean success) {
            calls.add("tool:" + name);
        }

        @Override
        public void assistant(String delta) {
            calls.add("assistant");
        }

        @Override
        public void plan(PlanView plan) {
            calls.add("plan:" + plan.state().name());
        }

        @Override
        public void step(StepView step) {
            calls.add("step:" + step.seq() + ":" + step.state().name());
        }
    }

    @Test
    void recordsAndForwardsInOrder() {
        Spy spy = new Spy();
        PlanProgressRecorder recorder = new PlanProgressRecorder(spy);
        recorder.phase("planning");
        recorder.plan(new PlanView("p1", PlanState.PENDING, List.of(new PlanStep(1, "一步", ""))));
        recorder.step(new StepView(1, "一步", StepState.RUNNING, ""));
        recorder.step(new StepView(1, "一步", StepState.DONE, "结果"));

        // 出站必须与记录同序，前端才有正确的进行态
        assertEquals(List.of("phase:planning", "plan:PENDING", "step:1:RUNNING", "step:1:DONE"),
                spy.calls);
        List<PlanProgressRecorder.Frame> frames = recorder.frames();
        assertEquals(3, frames.size());
        assertEquals("plan", frames.get(0).type().code());
        // 摘要给人看，正文是 JSON；两者不能互换
        assertTrue(frames.get(0).summary().contains("计划共 1 步"));
        assertTrue(frames.get(0).content().contains("\"planId\":\"p1\""));
        assertEquals("step", frames.get(1).type().code());
        assertTrue(frames.get(1).content().contains("\"state\":\"RUNNING\""));
    }

    @Test
    void drainEmitsEachFrameOnlyOnce() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);
        recorder.plan(new PlanView("p1", PlanState.EXECUTING, List.of()));
        assertEquals(1, recorder.drain().size());
        // 第二次 drain 必须为空：否则自动执行路径会把规划帧写两遍，时间线上出现两张计划卡
        assertTrue(recorder.drain().isEmpty());
        recorder.step(new StepView(1, "一步", StepState.DONE, "结果"));
        assertEquals(1, recorder.drain().size());
    }

    @Test
    void nullDelegateIsSafe() {
        PlanProgressRecorder recorder = new PlanProgressRecorder(null);
        // 同步 send 路径没有 SSE 出口，收集仍要正常
        recorder.phase("planning");
        recorder.plan(null);
        recorder.step(null);
        assertEquals(0, recorder.frames().size());
    }
}
