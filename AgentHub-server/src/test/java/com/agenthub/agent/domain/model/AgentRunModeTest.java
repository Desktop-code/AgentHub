package com.agenthub.agent.domain.model;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三种运行模式的解析与「是否单 Agent 绑定」，以及计划配置的默认与夹紧。
 */
class AgentRunModeTest {

    @Test
    void parsesThreeModesOnly() {
        assertEquals(AgentRunMode.REACT, AgentRunMode.parse(" REACT "));
        assertEquals(AgentRunMode.ROUTER, AgentRunMode.parse("ROUTER"));
        assertEquals(AgentRunMode.PLAN_AND_EXECUTE, AgentRunMode.parse("PLAN_AND_EXECUTE"));
        // Chat / Workflow / Multi-Agent 都不在枚举内，必须被拒
        assertThrows(BizException.class, () -> AgentRunMode.parse("CHAT"));
        assertThrows(BizException.class, () -> AgentRunMode.parse("WORKFLOW"));
        assertThrows(BizException.class, () -> AgentRunMode.parse("MULTI_AGENT"));
        assertThrows(BizException.class, () -> AgentRunMode.parse(null));
        assertNull(AgentRunMode.optional(" "));
    }

    @Test
    void planBindsSingleAgentLikeReact() {
        assertTrue(AgentRunMode.bindsSingleAgent("REACT"));
        assertTrue(AgentRunMode.bindsSingleAgent("PLAN_AND_EXECUTE"));
        // Router 父节点只做分发，工具/技能/记忆对它隐藏
        assertFalse(AgentRunMode.bindsSingleAgent("ROUTER"));
        assertFalse(AgentRunMode.bindsSingleAgent(null));
    }

    @Test
    void planDefaultsAreAutoConfirmEightStepsNoRetry() {
        AgentRuntimeConfig config = AgentRuntimeConfig.defaultsFor("PLAN_AND_EXECUTE");
        assertFalse(config.requirePlanConfirmOn());
        assertEquals(8, config.maxPlanStepsOrDefault());
        assertEquals(0, config.maxStepRetriesOrDefault());
    }

    @Test
    void planFieldsAreClamped() {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(
                "{\"requirePlanConfirm\":true,\"maxPlanSteps\":999,\"maxStepRetries\":9}",
                "PLAN_AND_EXECUTE");
        assertTrue(config.requirePlanConfirmOn());
        assertEquals(AgentRuntimeConfig.MAX_PLAN_STEPS_CAP, config.maxPlanStepsOrDefault());
        assertEquals(AgentRuntimeConfig.MAX_STEP_RETRIES_CAP, config.maxStepRetriesOrDefault());
        // 步数为 0 或负数回落到默认，不能变成「没有步骤」
        assertEquals(AgentRuntimeConfig.DEFAULT_MAX_PLAN_STEPS,
                AgentRuntimeConfig.parse("{\"maxPlanSteps\":0}", "PLAN_AND_EXECUTE").maxPlanStepsOrDefault());
        assertEquals(0, AgentRuntimeConfig.parse("{\"maxStepRetries\":-5}", "PLAN_AND_EXECUTE")
                .maxStepRetriesOrDefault());
    }

    @Test
    void roundTripKeepsBothGroups() {
        AgentRuntimeConfig source = new AgentRuntimeConfig();
        source.setRequirePlanConfirm(true);
        source.setMaxPlanSteps(5);
        source.setMaxStepRetries(2);
        String json = AgentRuntimeConfig.toStorageJson(source, "PLAN_AND_EXECUTE");
        AgentRuntimeConfig parsed = AgentRuntimeConfig.parse(json, "PLAN_AND_EXECUTE");
        assertTrue(parsed.requirePlanConfirmOn());
        assertEquals(5, parsed.maxPlanStepsOrDefault());
        assertEquals(2, parsed.maxStepRetriesOrDefault());
        // Router 那组字段仍在结构里，切模式时不至于丢
        assertEquals(List.of("CONCAT"), List.of(parsed.answerModeEnum().name()));
    }
}
