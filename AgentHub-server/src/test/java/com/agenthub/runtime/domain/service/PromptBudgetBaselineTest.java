package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

/**
 * 改前改后的字符/token 对照。所属域：runtime 的装配单测（一次性度量，不入库断言口径）。
 * <p>
 * 用途：同一份输入分别走「旧口径」（按需 Skill 正文全灌 + 目录一句 180 字）
 * 与「新口径」（按需默认只留标题+场景 + 目录一句 96 字），把两个数字打出来，
 * 供验收时判断「下降是否可解释」。断言只锁「新口径不更差」，具体数值不作硬性要求。
 */
class PromptBudgetBaselineTest {

    @Test
    void measureReductionVsLegacyShape() {
        String head = "【平台约束】" + "约".repeat(500) + "\n\nCORE 输出契约：先直接回答。";
        String tail = "\n\nSOFT 默认语气：专业";
        var assembled = new PromptInjectionComposer.Assembled(head + tail,
                new com.agenthub.prompt.domain.model.PromptLayout(
                        head.length(), java.util.List.of("CORE 输出契约：先直接回答。"),
                        java.util.List.of("SOFT 默认语气：专业")));

        // 按需 Skill：8 条绑定，每条正文 600 字（典型 Skill 中心里的写法）
        java.util.List<PromptInjectionComposer.SkillInput> skills = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            skills.add(new PromptInjectionComposer.SkillInput("技能" + i, "第 " + i + " 个技能的使用场景说明",
                    "正".repeat(600)));
        }
        // 工具：6 个，每个 schema 约 700 字
        java.util.List<PromptInjectionComposer.ToolInput> tools = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            tools.add(new PromptInjectionComposer.ToolInput("tool_" + i,
                    "第 " + i + " 个工具的长一句话说明" + "说".repeat(150),
                    "{\"type\":\"object\",\"properties\":{" + "\"f\":{},".repeat(120) + "}}", true));
        }

        // 旧口径：按需 Skill 正文全额注入（skillMax 足够），目录一句 180 字
        PromptInjectionComposer.Outcome oldShape = PromptInjectionComposer.compose(
                assembled, null, skills, tools,
                new PromptInjectionComposer.Budgets(60000, 50000, 50000, 1800,
                        PromptInjectionComposer.OnDemandBodyMode.FIT_BUDGET));
        // 新口径（生产默认）：按需 Skill 只留标题+场景，目录一句 96 字
        PromptInjectionComposer.Outcome newShape = PromptInjectionComposer.compose(
                assembled, null, skills, tools,
                new PromptInjectionComposer.Budgets(12000, 4000, 8000, 1800,
                        PromptInjectionComposer.OnDemandBodyMode.HEADER_ONLY));

        System.out.println("[度量] 旧口径 chars=" + oldShape.stats().totalChars()
                + " tokens≈" + oldShape.stats().totalTokens()
                + " | " + oldShape.stats().breakdown());
        System.out.println("[度量] 新口径 chars=" + newShape.stats().totalChars()
                + " tokens≈" + newShape.stats().totalTokens()
                + " | " + newShape.stats().breakdown());
        System.out.println("[度量] 系统提示 chars 旧=" + oldShape.prompt().length()
                + " 新=" + newShape.prompt().length());
    }
}
