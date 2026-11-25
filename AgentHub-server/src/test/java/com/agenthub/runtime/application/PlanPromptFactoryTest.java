package com.agenthub.runtime.application;

import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.runtime.domain.model.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划执行三段提示词：平台约束必须仍在最前，且步数上限与「只做本步」约束都要落到文本里。
 * 前缀若被业务段挤掉，{@code AssembledPromptGuard} 会在创建 Agent 时直接拒绝，故这里先测住。
 * 夹具用内存 L1，不连库。
 */
class PlanPromptFactoryTest {

    private final PlanPromptFactory factory = new PlanPromptFactory();
    /** 已装配的系统提示：平台约束 + 业务段（结构与生产 Assembler 输出一致） */
    private final String base = PromptTestFixtures.seedConstraintText() + "\n\n【业务提示词】\n你是执行助手。";

    @Test
    void planningPromptKeepsPrefixFirstAndSetsStepCap() {
        String prompt = factory.planningPrompt(base, 5);
        assertTrue(prompt.startsWith(PromptTestFixtures.seedConstraintText()));
        assertTrue(prompt.contains("最多 5 步"));
        assertTrue(prompt.contains("只输出 JSON 数组"));
        // 计划阶段不得允许规划未绑定工具
        assertTrue(prompt.contains("不要规划未提供的工具"));
    }

    @Test
    void stepPromptMarksCurrentStepAndListsDone() {
        List<PlanStep> steps = List.of(
                new PlanStep(1, "查天气", "北京"),
                new PlanStep(2, "给建议", ""));
        String prompt = factory.stepPrompt(base, steps, steps.get(1), "北京天气怎么样", List.of("晴 25℃"));
        assertTrue(prompt.startsWith(PromptTestFixtures.seedConstraintText()));
        assertTrue(prompt.contains("第 2 步"));
        // 已完成步骤结果要带进来，后一步才能接上
        assertTrue(prompt.contains("晴 25℃"));
        // 用户原始请求必须带上：步骤标题常省略主语，缺原问就无法确定查谁
        assertTrue(prompt.contains("北京天气怎么样"));
        assertTrue(prompt.contains("只完成第 2 步"));
        assertFalse(prompt.contains("→ 2. 给建议（"));
    }

    @Test
    void summaryPromptAsksForOneCoherentAnswer() {
        String prompt = factory.summaryPrompt(base, "北京天气怎么样", List.of("晴 25℃"));
        assertTrue(prompt.startsWith(PromptTestFixtures.seedConstraintText()));
        assertTrue(prompt.contains("不要罗列步骤编号"));
        assertTrue(prompt.contains("北京天气怎么样"));
        // 汇总的用户侧复用已注入记忆的文本，追问场景仍有上文
        assertEquals(true, factory.summaryUser("摘要+近轮+北京天气怎么样", List.of("晴 25℃")).contains("晴 25℃"));
    }
}
