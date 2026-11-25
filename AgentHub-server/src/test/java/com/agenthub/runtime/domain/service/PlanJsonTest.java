package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepState;
import com.agenthub.runtime.domain.model.StepView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划事件正文的形状：落库与出站必须同一套字段。
 */
class PlanJsonTest {

    @Test
    void planRoundTripKeepsStepsAndState() {
        PlanView view = new PlanView("plan-abc", PlanState.PENDING,
                List.of(new PlanStep(1, "查天气", "北京"), new PlanStep(2, "给建议", "")));
        String json = PlanJson.planPayload(view);
        PlanView parsed = PlanJson.parsePlan(json).orElseThrow();
        assertEquals("plan-abc", parsed.planId());
        assertEquals(PlanState.PENDING, parsed.state());
        assertEquals(2, parsed.steps().size());
        assertEquals("查天气", parsed.steps().get(0).title());
        assertEquals("北京", parsed.steps().get(0).detail());
    }

    @Test
    void stateOnlyPlanHasNoSteps() {
        PlanView view = new PlanView("plan-abc", PlanState.EXECUTING, List.of());
        PlanView parsed = PlanJson.parsePlan(PlanJson.planPayload(view)).orElseThrow();
        assertEquals(PlanState.EXECUTING, parsed.state());
        assertTrue(parsed.steps().isEmpty());
    }

    @Test
    void stepPayloadCarriesStateAndText() {
        String json = PlanJson.stepPayload(new StepView(2, "查天气", StepState.DONE, "晴 25℃"));
        assertTrue(json.contains("\"seq\":2"));
        assertTrue(json.contains("\"state\":\"DONE\""));
        assertTrue(json.contains("晴 25℃"));
    }

    @Test
    void unknownStateIsNotReportedAsDone() {
        // 状态缺失按失败处理：伪装成完成会让界面给出错误结论
        PlanView parsed = PlanJson.parsePlan("{\"planId\":\"p\",\"steps\":[]}").orElseThrow();
        assertEquals(PlanState.FAILED, parsed.state());
    }

    @Test
    void blankOrGarbageReturnsEmpty() {
        assertTrue(PlanJson.parsePlan(null).isEmpty());
        assertTrue(PlanJson.parsePlan("not json").isEmpty());
    }
}
