package com.agenthub.runtime.domain.service;

import com.agenthub.prompt.domain.model.PromptLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算内全文；超预算可调用工具不得「仅名称可调用」；空 schema 不得误杀；callable=false 标本轮不可用。
 */
class PromptInjectionComposerTest {

    /**
     * 默认形态（HEADER_ONLY）：按需 Skill 只给「标题 + 场景」，正文不进 system。
     * <p>
     * 这是 P0 预算收敛的核心行为：正文动辄几百上千字，而模型判断「该不该用」只需标题 + 场景。
     */
    @Test
    void defaultModeInjectsOnlySkillHeaderNotBody() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(
                        new PromptInjectionComposer.SkillInput("查询", "查订单", "全文一二三"),
                        new PromptInjectionComposer.SkillInput("写作", "写周报", "长".repeat(200))
                ),
                List.of(
                        new PromptInjectionComposer.ToolInput("lookup", "查一条", "{\"a\":1}", true),
                        new PromptInjectionComposer.ToolInput("mcp_search", "搜一下", "{\"huge\":true}", false)
                ),
                new PromptInjectionComposer.Budgets(100, 80, 8));
        // 段头必须在：标题 + 场景，两条都在
        assertTrue(outcome.prompt().contains("## Skill 查询"));
        assertTrue(outcome.prompt().contains("场景：查订单"));
        assertTrue(outcome.prompt().contains("场景：写周报"));
        // 正文默认不进：这是本次预算收敛的主要来源
        assertFalse(outcome.prompt().contains("全文一二三"));
        assertFalse(outcome.prompt().contains("长长长长长"));
        assertTrue(outcome.prompt().contains("lookup"));
        assertTrue(outcome.prompt().contains("mcp_search"));
        assertTrue(outcome.prompt().contains("本轮不可用"));
        assertFalse(outcome.prompt().contains("未绑定"));
        assertEquals(List.of("lookup"), outcome.schemaNames());
        assertTrue(outcome.omittedCallableNames().isEmpty());
        assertFalse(outcome.stats().isToolSchemaBudgetHit());
        assertEquals(2, outcome.stats().getToolsInjected());
        assertEquals(2, outcome.stats().getSkillsInjected());
    }

    /**
     * 显式开启 FIT_BUDGET：额度内正文进 system，放不下的那条只留段头并标记截断。
     */
    @Test
    void fitBudgetModeInjectsBodyWithinBudget() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(
                        new PromptInjectionComposer.SkillInput("查询", "查订单", "全文一二三"),
                        new PromptInjectionComposer.SkillInput("写作", "写周报", "长".repeat(200))
                ),
                List.of(),
                new PromptInjectionComposer.Budgets(100, 120, 8000, 1800,
                        PromptInjectionComposer.OnDemandBodyMode.FIT_BUDGET));
        assertTrue(outcome.prompt().contains("全文一二三"), "额度内正文应进 system");
        assertTrue(outcome.prompt().contains("场景：写周报"), "放不下的那条仍保留段头");
        assertFalse(outcome.prompt().contains("长长长长长"), "超出额度的正文不进 system");
        assertTrue(outcome.stats().isPromptSegmentTruncated());
        assertEquals(2, outcome.stats().getSkillsInjected());
    }

    @Test
    void overBudgetCallableIsNotNameOnlyExposed() {
        String huge = "{\"properties\":{" + "\"x\":{}".repeat(40) + "}}";
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(),
                List.of(
                        new PromptInjectionComposer.ToolInput("small", "小工具", "{\"a\":1}", true),
                        new PromptInjectionComposer.ToolInput("hugeTool", "超大 schema", huge, true)
                ),
                new PromptInjectionComposer.Budgets(100, 100, 20));
        assertTrue(outcome.prompt().contains("small"));
        assertEquals(List.of("small"), outcome.schemaNames());
        // 超预算：不得出现在可调用目录里（半套注入）
        assertFalse(outcome.prompt().contains("hugeTool"));
        assertTrue(outcome.omittedCallableNames().contains("hugeTool"));
        assertTrue(outcome.stats().isTruncated());
        assertTrue(outcome.stats().isToolSchemaBudgetHit());
        assertFalse(outcome.stats().isPromptSegmentTruncated());
        assertEquals(1, outcome.stats().getToolsInjected());
    }

    @Test
    void unboundToolIsAbsentBecauseCallerDidNotPassIt() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统", List.of(), List.of(), new PromptInjectionComposer.Budgets(100, 100, 100));
        assertFalse(outcome.prompt().contains("secret_tool"));
        assertEquals(0, outcome.stats().getToolsInjected());
    }

    @Test
    void singleQueryWeatherWithEmptyDbSchemaIsFullyExposedUnderDefaultLikeBudget() {
        // 对齐线上：builtin 的 input_schema_json 常为 null，默认 tools-schema-max-chars=8000
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "平台前缀\n你是问答助手",
                List.of(),
                List.of(new PromptInjectionComposer.ToolInput(
                        "queryWeather", "查询城市天气", null, true)),
                new PromptInjectionComposer.Budgets(12000, 8000, 8000));
        assertEquals(List.of("queryWeather"), outcome.schemaNames());
        assertTrue(outcome.omittedCallableNames().isEmpty());
        assertTrue(outcome.prompt().contains("queryWeather"));
        assertTrue(outcome.prompt().contains("查询城市天气"));
        assertFalse(outcome.stats().isToolSchemaBudgetHit());
        assertFalse(outcome.stats().isTruncated());
        assertEquals(1, outcome.stats().getToolsInjected());
        assertTrue(outcome.stats().getToolSchemaChars() >= PromptInjectionComposer.schemaCost(
                "queryWeather", "查询城市天气", null));
    }

    @Test
    void tinySchemaBudgetAllowsZeroToolsWithBudgetHitFlag() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(),
                List.of(new PromptInjectionComposer.ToolInput(
                        "queryWeather", "查询城市天气", null, true)),
                new PromptInjectionComposer.Budgets(100, 100, 8));
        assertTrue(outcome.schemaNames().isEmpty());
        assertEquals(List.of("queryWeather"), outcome.omittedCallableNames());
        assertFalse(outcome.prompt().contains("queryWeather"));
        assertTrue(outcome.stats().isToolSchemaBudgetHit());
        assertFalse(outcome.stats().isPromptSegmentTruncated());
        assertTrue(outcome.prompt().contains("超预算可调用工具未对本轮暴露"));
    }

    /**
     * Skill 段被让出不等于工具额度命中：两类截断必须在统计上分开，页脚文案也不同。
     * <p>
     * 用 FIT_BUDGET 模式构造「正文放不下」的让出，验证它会标 segment 截断而不标 schema 命中。
     */
    @Test
    void skillTruncationDoesNotClaimToolBudgetHitWhenToolsFit() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(new PromptInjectionComposer.SkillInput("写作", "写周报", "长".repeat(200))),
                List.of(new PromptInjectionComposer.ToolInput("queryWeather", "天气", "", true)),
                new PromptInjectionComposer.Budgets(100, 40, 8000, 1800,
                        PromptInjectionComposer.OnDemandBodyMode.FIT_BUDGET));
        assertEquals(List.of("queryWeather"), outcome.schemaNames());
        assertTrue(outcome.omittedCallableNames().isEmpty());
        assertTrue(outcome.stats().isPromptSegmentTruncated());
        assertFalse(outcome.stats().isToolSchemaBudgetHit());
        assertTrue(outcome.prompt().contains("系统提示或 Skill 已按预算截断"));
        assertFalse(outcome.prompt().contains("超预算可调用工具未对本轮暴露"));
    }

    @Test
    void bindOrderGreedyPacksFittingSchemasAndOmitsOversized() {
        // first 小、second 超大跳过、third 仍可塞入剩余额度
        String huge = "{\"k\":\"" + "m".repeat(80) + "\"}";
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(),
                List.of(
                        new PromptInjectionComposer.ToolInput("first", "一", "{\"a\":1}", true),
                        new PromptInjectionComposer.ToolInput("second", "二", huge, true),
                        new PromptInjectionComposer.ToolInput("third", "三", "{\"b\":2}", true)
                ),
                new PromptInjectionComposer.Budgets(100, 100, 40));
        assertEquals(List.of("first", "third"), outcome.schemaNames());
        assertEquals(List.of("second"), outcome.omittedCallableNames());
        assertTrue(outcome.prompt().contains("first"));
        assertFalse(outcome.prompt().contains("second"));
        assertTrue(outcome.prompt().contains("third"));
    }

    @Test
    void mcpAndBuiltinSameBudgetInjectInBindOrderWhenCallable() {
        // 模拟修复后：MCP 也 callable=true，与内置同一套预算、按绑定序
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(),
                List.of(
                        new PromptInjectionComposer.ToolInput(
                                "system_user_list", "用户列表", "{\"type\":\"object\"}", true),
                        new PromptInjectionComposer.ToolInput(
                                "queryWeather", "天气", "{\"type\":\"object\",\"properties\":{\"city\":{}}}", true)
                ),
                new PromptInjectionComposer.Budgets(100, 100, 8000));
        assertEquals(List.of("system_user_list", "queryWeather"), outcome.schemaNames());
        assertTrue(outcome.omittedCallableNames().isEmpty());
        assertFalse(outcome.prompt().contains("本轮不可用"));
        assertTrue(outcome.prompt().indexOf("system_user_list") < outcome.prompt().indexOf("queryWeather"));
    }

    /**
     * 硬触发：命中关键词的 Skill 必须整段正文进上下文，并带固定前缀。
     */
    @Test
    void triggeredSkillInjectsFullBodyWithFixedPrefix() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(new PromptInjectionComposer.SkillInput("脱敏规范", "处理敏感数据时使用", "必须打码手机号", true)),
                List.of(),
                new PromptInjectionComposer.Budgets(100, 8000, 8000));
        assertTrue(outcome.prompt().contains("用户触发了【脱敏规范】，请严格按照以下规则执行："));
        assertTrue(outcome.prompt().contains("必须打码手机号"));
        // 目录口径：硬触发也要带场景（description）
        assertTrue(outcome.prompt().contains("场景：处理敏感数据时使用"));
        assertEquals(1, outcome.stats().getSkillsInjected());
        assertEquals(1, outcome.stats().getSkillsHardTriggered());
    }

    /**
     * 未触发时不加前缀，按默认形态只给「标题 + 场景」，正文不进 system。
     */
    @Test
    void untriggeredSkillUsesCatalogFormWithoutPrefix() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(new PromptInjectionComposer.SkillInput("摘要规范", "需要压缩长文时使用", "三句话以内", false)),
                List.of(),
                new PromptInjectionComposer.Budgets(100, 8000, 8000));
        assertFalse(outcome.prompt().contains("用户触发了【"));
        assertTrue(outcome.prompt().contains("## Skill 摘要规范"));
        assertTrue(outcome.prompt().contains("场景：需要压缩长文时使用"));
        // 默认 HEADER_ONLY：正文不进 system
        assertFalse(outcome.prompt().contains("三句话以内"));
        assertEquals(0, outcome.stats().getSkillsHardTriggered());
        assertEquals(1, outcome.stats().getSkillsInjected());
    }

    /**
     * 多技能可同时硬触发；且硬触发不被同轮按需 Skill 挤掉（先占预算）。
     */
    @Test
    void multipleTriggeredSkillsAllInjectEvenWhenOnDemandSkillIsGreedy() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(
                        // 按需项排在前面且正文极长，模拟「先绑的按需 Skill 吃满预算」
                        new PromptInjectionComposer.SkillInput("长文", "写长文", "长".repeat(400), false),
                        new PromptInjectionComposer.SkillInput("脱敏规范", "处理敏感数据", "打码手机号", true),
                        new PromptInjectionComposer.SkillInput("摘要规范", "压缩长文", "三句话以内", true)
                ),
                List.of(),
                new PromptInjectionComposer.Budgets(100, 200, 8000));
        assertTrue(outcome.prompt().contains("用户触发了【脱敏规范】"));
        assertTrue(outcome.prompt().contains("打码手机号"));
        assertTrue(outcome.prompt().contains("用户触发了【摘要规范】"));
        assertTrue(outcome.prompt().contains("三句话以内"));
        assertEquals(2, outcome.stats().getSkillsHardTriggered());
        // 两条强制 Skill 都完整在场；按需项只剩标题+场景，正文被预算挡下
        assertFalse(outcome.prompt().contains("长".repeat(50)));
        assertEquals(3, outcome.stats().getSkillsInjected());
    }

    /**
     * 目录里永远带 description（场景），不能只剩标题——否则模型无法判断何时加载。
     * <p>
     * 用 FIT_BUDGET 构造「正文被额度挡下」的场景：正文不进，但段头（标题 + 场景）必须在。
     */
    @Test
    void catalogAlwaysCarriesSceneWhenBodyIsTrimmedByBudget() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "系统",
                List.of(new PromptInjectionComposer.SkillInput("写作", "写周报时加载", "长".repeat(400))),
                List.of(),
                new PromptInjectionComposer.Budgets(100, 60, 8000, 1800,
                        PromptInjectionComposer.OnDemandBodyMode.FIT_BUDGET));
        assertTrue(outcome.prompt().contains("## Skill 写作"));
        assertTrue(outcome.prompt().contains("场景：写周报时加载"));
        assertFalse(outcome.prompt().contains("长".repeat(10)));
        assertTrue(outcome.stats().isPromptSegmentTruncated());
    }

    /**
     * 受保护 head 永不被二次截断：预算给得比 head 还小，head 也必须原样保留。
     * 这是「锁定契约被截成半句仍出站」这类事故的唯一防线。
     */
    @Test
    void protectedHeadSurvivesEvenWhenBudgetSmallerThanHead() {
        String head = "【平台约束】不得输出密钥。\n\nCORE 输出契约：先直接回答。";
        String tail = "\n\nSOFT 默认语气：专业、简洁。";
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                head + tail,
                new PromptLayout(head.length(), List.of("CORE 输出契约：先直接回答。"), List.of("SOFT 默认语气：专业、简洁。")),
                null,
                List.of(),
                List.of(),
                new PromptInjectionComposer.Budgets(10, 100, 100));
        assertTrue(outcome.prompt().startsWith("【平台约束】"), "L1 必须完整在最前");
        assertTrue(outcome.prompt().contains("CORE 输出契约：先直接回答。"), "锁定段必须完整保留");
        assertTrue(outcome.stats().isPromptSegmentTruncated());
        assertEquals(head.length(), outcome.stats().getProtectedChars());
    }

    /**
     * 削减优先级：先让出记忆追加区，再让出按需 Skill 正文，最后才压目录文案；
     * 硬触发 Skill 与可调用工具名保到最后一档。
     */
    @Test
    void memoryYieldsBeforeOnDemandSkillAndCatalog() {
        String head = "HEAD";
        String tail = "\n\nSOFT 段";
        String memory = "【用户偏好】\n- 常住城市：杭州";
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                head + tail,
                new PromptLayout(head.length(), List.of(), List.of("SOFT 段")),
                memory,
                List.of(
                        new PromptInjectionComposer.SkillInput("按需", "写周报", "按需正文" + "长".repeat(60)),
                        new PromptInjectionComposer.SkillInput("强制", "脱敏", "强制正文", true)
                ),
                List.of(new PromptInjectionComposer.ToolInput("queryWeather", "查询城市天气", "{}", true)),
                // 预算刚好够 head + tail + 强制 Skill + 目录，记忆（约 20 字）必须让出
                new PromptInjectionComposer.Budgets(head.length() + tail.length() + 95, 200, 100));
        String prompt = outcome.prompt();
        assertTrue(prompt.startsWith("HEAD"));
        assertTrue(prompt.contains("用户触发了【强制】，请严格按照以下规则执行："), "硬触发必须保留");
        assertTrue(prompt.contains("## 工具目录"), "工具目录必须保留");
        assertTrue(prompt.contains("queryWeather"), "可调用工具名必须保留");
        assertTrue(prompt.indexOf("## 工具目录") > prompt.indexOf("用户触发了【强制】"), "目录仍在 Skill 之后");
        assertFalse(prompt.contains("常住城市：杭州"), "记忆必须先让出");
    }

    /**
     * 记忆段在组合器里位于 Skill 与工具目录之前——顺序由本类决定，不能再靠调用方尾部追加。
     */
    @Test
    void memoryPlacedBetweenAssembledTailAndSkill() {
        String head = "HEAD";
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                head,
                new PromptLayout(head.length(), List.of(), List.of()),
                "【工作记忆】\n- 上次结论：用西湖区口径",
                List.of(new PromptInjectionComposer.SkillInput("写作", "写周报", "正文")),
                List.of(new PromptInjectionComposer.ToolInput("queryWeather", "查询城市天气", "{}", true)),
                new PromptInjectionComposer.Budgets(4000, 4000, 4000));
        String prompt = outcome.prompt();
        int memory = prompt.indexOf("【工作记忆】");
        int skill = prompt.indexOf("## Skill 写作");
        int catalog = prompt.indexOf("## 工具目录");
        assertTrue(memory > 0, "记忆段必须在场");
        assertTrue(skill > memory, "Skill 在记忆之后");
        assertTrue(catalog > skill, "工具目录在 Skill 之后");
        assertTrue(outcome.stats().getMemoryChars() > 0);
    }

    /**
     * 无结构信息时按整段可裁处理（兼容老入口），但受保护长度上报为 0，便于排障识别未走正式链路。
     */
    @Test
    void legacyEntryReportsNoProtectedChars() {
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                "很长".repeat(100), List.of(), List.of(), new PromptInjectionComposer.Budgets(50, 50, 50));
        assertEquals(0, outcome.stats().getProtectedChars());
        assertTrue(outcome.stats().isPromptSegmentTruncated());
    }
}
