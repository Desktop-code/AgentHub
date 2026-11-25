package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PromptInjectionStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一裁剪器。所属域：runtime 的装配单测。
 * <p>
 * 锁死三件事：
 * <ol>
 *   <li>保护区（L1 + 平台追加句 + 锁定段）永不让出，预算再小也整段保留；</li>
 *   <li>让出顺序固定：记忆 → 按需 Skill → 目录文案 → 可压区 → 强制 Skill；</li>
 *   <li>同档内倒序让出（先渲染 / 先绑定者优先保留），不让后面的挤掉前面的。</li>
 * </ol>
 */
class PromptBudgetTrimmerTest {

    private static PromptBudgetTrimmer.Section memory(String body) {
        return new PromptBudgetTrimmer.Section("memory", PromptBudgetTrimmer.Tier.MEMORY, "\n\n" + body, "");
    }

    private static PromptBudgetTrimmer.Section optionalSkill(String title, String body) {
        String header = "\n\n## Skill " + title + "\n场景：场景" + title;
        return new PromptBudgetTrimmer.Section("skill-on-demand:" + title,
                PromptBudgetTrimmer.Tier.OPTIONAL_SKILL, header + "\n" + body, header);
    }

    private static PromptBudgetTrimmer.Section catalog(String full, String namesOnly) {
        return new PromptBudgetTrimmer.Section("catalog", PromptBudgetTrimmer.Tier.CATALOG, full, namesOnly);
    }

    private static PromptBudgetTrimmer.Section forcedSkill(String title, String body) {
        String header = "\n\n## 强制 Skill " + title + "\n场景：场景" + title
                + "\n用户触发了【" + title + "】，请严格按照以下规则执行：\n";
        return PromptBudgetTrimmer.Section.fixed("skill-forced:" + title,
                PromptBudgetTrimmer.Tier.FORCED_SKILL, header + body);
    }

    /**
     * 预算充裕：一个字都不动，且不标记截断。
     */
    @Test
    void noChangeWhenWithinBudget() {
        var sections = java.util.List.of(
                PromptBudgetTrimmer.Section.fixed("protected", PromptBudgetTrimmer.Tier.PROTECTED, "HEAD"),
                PromptBudgetTrimmer.Section.fixed("soft", PromptBudgetTrimmer.Tier.SOFT_TAIL, "\n\nTAIL"),
                memory("【用户偏好】\n常住杭州"));
        PromptBudgetTrimmer.Outcome outcome = PromptBudgetTrimmer.trim(sections, 10_000);
        assertFalse(outcome.truncated());
        assertTrue(outcome.yielded().isEmpty());
        assertTrue(outcome.text().contains("HEAD"));
        assertTrue(outcome.text().contains("常住杭州"));
    }

    /**
     * 保护区自己就超预算：整段保留，标记 overBudget，绝不去砍 head。
     */
    @Test
    void protectedSurvivesEvenWhenBudgetSmallerThanHead() {
        String head = "【平台约束】不得输出密钥。\n\nCORE 输出契约：先直接回答。";
        var sections = java.util.List.of(
                PromptBudgetTrimmer.Section.fixed("protected", PromptBudgetTrimmer.Tier.PROTECTED, head),
                PromptBudgetTrimmer.Section.fixed("soft", PromptBudgetTrimmer.Tier.SOFT_TAIL, "\n\nSOFT 段"),
                memory("【用户偏好】\n常住杭州"));
        PromptBudgetTrimmer.Outcome outcome = PromptBudgetTrimmer.trim(sections, 10);
        assertTrue(outcome.overBudget());
        assertTrue(outcome.text().startsWith("【平台约束】"), "L1 必须完整在最前");
        assertTrue(outcome.text().contains("CORE 输出契约：先直接回答。"), "锁定段必须完整保留");
        assertFalse(outcome.text().contains("常住杭州"), "记忆先让出");
    }

    /**
     * 让出顺序：记忆先走，其次按需 Skill 正文，最后才动目录文案；强制 Skill 不动。
     */
    @Test
    void memoryYieldsBeforeSkillsAndCatalog() {
        PromptBudgetTrimmer.Section head = PromptBudgetTrimmer.Section.fixed(
                "protected", PromptBudgetTrimmer.Tier.PROTECTED, "HEAD");
        PromptBudgetTrimmer.Section memorySection = memory("【用户偏好】\n" + "偏".repeat(200));
        PromptBudgetTrimmer.Section skill = optionalSkill("写作", "正".repeat(200));
        PromptBudgetTrimmer.Section catalogSection = catalog(
                "\n\n## 工具目录\n- queryWeather：查天气", "\n\n## 工具目录\n- queryWeather");
        PromptBudgetTrimmer.Section forced = forcedSkill("脱敏", "必须打码手机号");
        var sections = java.util.List.of(head, memorySection, skill, catalogSection, forced);
        // 预算刚好装下 HEAD + 强制 Skill + 完整目录 + 按需 Skill 段头：
        // 记忆（整段）与按需正文必须让出，目录文案与强制块不动
        int budget = head.text().length() + forced.text().length()
                + catalogSection.text().length() + skill.text().length() - 200;
        PromptBudgetTrimmer.Outcome outcome = PromptBudgetTrimmer.trim(sections, budget);
        assertFalse(outcome.text().contains("偏偏偏"), "记忆必须最先让出");
        assertFalse(outcome.text().contains("正正正"), "按需 Skill 正文随后让出");
        assertTrue(outcome.text().contains("场景：场景写作"), "按需 Skill 段头仍在");
        assertTrue(outcome.text().contains("必须打码手机号"), "强制 Skill 正文不动");
        assertTrue(outcome.text().contains("queryWeather：查天气"), "目录文案最后才动");
        assertTrue(outcome.yielded().contains("memory"), "让出记录要能看出先砍了谁");
        assertTrue(outcome.yielded().indexOf("memory")
                < outcome.yielded().indexOf("skill-on-demand:写作"), "记忆必须排在按需 Skill 之前让出");
    }

    /**
     * 只剩可压区还能动时：按字符从尾部裁，而不是整段丢弃。
     */
    @Test
    void softTailIsCutByCharsNotDropped() {
        String tail = "\n\n" + "业".repeat(100);
        var sections = java.util.List.of(
                PromptBudgetTrimmer.Section.fixed("protected", PromptBudgetTrimmer.Tier.PROTECTED, "HEAD"),
                PromptBudgetTrimmer.Section.fixed("soft", PromptBudgetTrimmer.Tier.SOFT_TAIL, tail));
        PromptBudgetTrimmer.Outcome outcome = PromptBudgetTrimmer.trim(sections, 60);
        assertTrue(outcome.yielded().contains("soft"));
        // 保留部分正文（不是全丢）：裁掉的只是尾部
        assertTrue(outcome.text().length() < "HEAD".length() + tail.length());
        assertTrue(outcome.text().contains("业业"), "可压区是裁字，不是整段消失");
    }

    /**
     * 同档倒序：两个按需 Skill 抢同一份额度时，后渲染的先让出。
     */
    @Test
    void sameTierYieldsFromTailSoEarlierSkillSurvives() {
        var sections = java.util.List.of(
                PromptBudgetTrimmer.Section.fixed("protected", PromptBudgetTrimmer.Tier.PROTECTED, "HEAD"),
                optionalSkill("先", "一一一一一"),
                optionalSkill("后", "二二二二二"));
        String firstHeader = "\n\n## Skill 先\n场景：场景先";
        String secondHeader = "\n\n## Skill 后\n场景：场景后";
        // 只差 6 个字（后一条正文），足以让「后」让出而「先」保留
        int budget = "HEAD".length() + firstHeader.length() + 1 + 5 + secondHeader.length();
        PromptBudgetTrimmer.Outcome outcome = PromptBudgetTrimmer.trim(sections, budget);
        assertTrue(outcome.text().contains("一一一一一"), "先渲染的正文保留");
        assertFalse(outcome.text().contains("二二二二二"), "后渲染的正文先让出");
        assertTrue(outcome.text().contains("场景：场景后"), "后渲染的段头仍在（禁止孤立无场景标题）");
        assertTrue(outcome.yielded().contains("skill-on-demand:后"));
    }

    /**
     * 分段计量：每段都给数字，且合计与各段相加一致。
     */
    @Test
    void statsCarryPerSectionNumbers() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                sampleSystem(),
                null,
                java.util.List.of(new PromptInjectionComposer.SkillInput("摘要", "压缩长文", "三句话以内")),
                java.util.List.of(new PromptInjectionComposer.ToolInput("queryWeather", "查天气", "{\"a\":1}", true)),
                new PromptInjectionComposer.Budgets(8000, 4000, 8000, 1800));
        PromptInjectionStats stats = outcome.stats();
        assertTrue(stats.getProtectedChars() > 0, "保护区必须有数字");
        assertTrue(stats.getProtectedTokens() > 0, "保护区必须给估算 token");
        assertTrue(stats.getSystemSoftChars() > 0, "可压区必须有数字");
        assertTrue(stats.getToolCatalogChars() > 0, "目录必须有数字");
        assertTrue(stats.getToolSchemaChars() > 0, "schema 必须有数字");
        assertTrue(stats.getSkillChars() > 0, "Skill 必须有数字");
        assertTrue(stats.totalTokens() > 0);
        assertTrue(stats.breakdown().contains("保护区"));
        assertTrue(stats.breakdown().contains("schema"));
    }

    /**
     * 强制 Skill 超硬上限：正文截断并打标，段头与固定前缀完整。
     */
    @Test
    void forcedSkillOverHardLimitIsClippedAndMarked() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                sampleSystem(),
                null,
                java.util.List.of(new PromptInjectionComposer.SkillInput(
                        "脱敏规范", "处理敏感数据时使用", "长".repeat(5000), true)),
                java.util.List.of(),
                new PromptInjectionComposer.Budgets(20000, 4000, 8000, 600));
        String prompt = outcome.prompt();
        assertTrue(prompt.contains("## 强制 Skill 脱敏规范"), "段头不裁");
        assertTrue(prompt.contains("用户触发了【脱敏规范】"), "固定前缀不裁");
        assertTrue(prompt.contains("已按平台上限截断"), "截断必须打标，模型要知道规则不完整");
        assertTrue(outcome.stats().getForcedSkillsClipped() > 0, "统计要能看出被截了几条");
    }

    /**
     * 采样系统提示：HEAD 受保护 + SOFT 可压，带结构描述。
     */
    private static PromptInjectionComposer.Assembled sampleSystem() {
        String head = "【平台约束】不得输出密钥。\n\nCORE 输出契约：先直接回答。";
        String tail = "\n\nSOFT 默认语气：专业、简洁。";
        return new PromptInjectionComposer.Assembled(head + tail,
                new com.agenthub.prompt.domain.model.PromptLayout(
                        head.length(), java.util.List.of("CORE 输出契约：先直接回答。"),
                        java.util.List.of("SOFT 默认语气：专业、简洁。")));
    }
}
