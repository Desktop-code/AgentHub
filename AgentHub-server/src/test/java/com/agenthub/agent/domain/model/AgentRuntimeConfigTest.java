package com.agenthub.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Router 运行配置默认与夹紧。
 */
class AgentRuntimeConfigTest {

    @Test
    void defaultsMultiIntentOnAndConcat() {
        AgentRuntimeConfig config = AgentRuntimeConfig.defaultsFor("ROUTER");
        assertTrue(config.multiIntentOn());
        assertEquals(RouterAnswerMode.CONCAT, config.answerModeEnum());
        assertEquals(3, config.maxRouteChildrenOrDefault());
    }

    @Test
    void parseBlankUsesDefaults() {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(null, "ROUTER");
        assertTrue(config.multiIntentOn());
        assertEquals("CONCAT", config.getAnswerMode());
    }

    @Test
    void clampsMaxAndUnknownMode() {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(
                "{\"multiIntentEnabled\":false,\"answerMode\":\"NOPE\",\"maxRouteChildren\":99}",
                "ROUTER");
        assertFalse(config.multiIntentOn());
        assertEquals(RouterAnswerMode.CONCAT, config.answerModeEnum());
        assertEquals(5, config.maxRouteChildrenOrDefault());
    }

    @Test
    void maTemplateThreeValuesPersisted() {
        // 旧数据无字段、默认配置都回落 ROUTER_PARALLEL；显式选的固定模板必须原样保留
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.parse(null, "ROUTER").maTemplateOrDefault());
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.defaultsFor("ROUTER").maTemplateOrDefault());
        assertEquals("FIXED_SEQUENCE", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_SEQUENCE\"}", "ROUTER").maTemplateOrDefault());
        assertEquals("FIXED_PARALLEL", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_PARALLEL\"}", "ROUTER").maTemplateOrDefault());
        // 非法值回落默认，禁止把用户选的固定模板洗回 ROUTER
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"NOPE\"}", "ROUTER").maTemplateOrDefault());
    }

    @Test
    void maTemplateWrittenIntoStorageJson() {
        AgentRuntimeConfig source = new AgentRuntimeConfig();
        source.setMaTemplate("FIXED_PARALLEL");
        String json = AgentRuntimeConfig.toStorageJson(source, "ROUTER");
        assertTrue(json.contains("\"maTemplate\":\"FIXED_PARALLEL\""), json);
    }

    /**
     * Router 提示词绑定：模板 / 版本 / soft 覆盖 / 追加区四项按原值往返。
     *
     * <p>这是「页面可配提示词」的落库形态；执行侧 {@code SystemPromptAssembler#assembleRouter}
     * 直接读这四项，因此序列化不能丢字段、也不能把覆盖值洗成空。
     */
    @Test
    void routerPromptBindingRoundTrips() {
        AgentRuntimeConfig source = new AgentRuntimeConfig();
        source.setMaJudgePromptTemplateId(31L);
        source.setMaJudgePromptTemplateVersion(2);
        source.setMaJudgePromptOverrideJson("{\"route_style\":\"只写一句依据\"}");
        source.setMaJudgePromptExtra("额外要求：先看候选简介再判断");

        AgentRuntimeConfig parsed = AgentRuntimeConfig.parse(
                AgentRuntimeConfig.toStorageJson(source, "ROUTER"), "ROUTER");

        assertEquals(31L, parsed.getMaJudgePromptTemplateId());
        assertEquals(2, parsed.getMaJudgePromptTemplateVersion());
        assertEquals("{\"route_style\":\"只写一句依据\"}", parsed.getMaJudgePromptOverrideJson());
        assertEquals("额外要求：先看候选简介再判断", parsed.getMaJudgePromptExtra());
    }

    /**
     * 半钉死状态必须被清掉：只有模板 id 没有版本，或没有模板却有覆盖值，
     * 都会让执行侧读到无法解释的配置，宁可当作「未配置」。
     */
    @Test
    void routerPromptHalfPinnedIsDropped() {
        AgentRuntimeConfig idOnly = new AgentRuntimeConfig();
        idOnly.setMaJudgePromptTemplateId(31L);
        AgentRuntimeConfig parsed = AgentRuntimeConfig.parse(
                AgentRuntimeConfig.toStorageJson(idOnly, "ROUTER"), "ROUTER");
        assertNull(parsed.getMaJudgePromptTemplateId());
        assertNull(parsed.getMaJudgePromptTemplateVersion());

        AgentRuntimeConfig orphanOverride = new AgentRuntimeConfig();
        orphanOverride.setMaJudgePromptOverrideJson("{\"route_style\":\"x\"}");
        assertNull(AgentRuntimeConfig.parse(
                AgentRuntimeConfig.toStorageJson(orphanOverride, "ROUTER"), "ROUTER")
                .getMaJudgePromptOverrideJson());
    }

    @Test
    void fixedSequenceDefaultsPriorSummaryOnly() {
        // 串行模板未显式选上下文策略时默认「前置摘要」，其它模板一律收敛回隔离
        AgentRuntimeConfig sequence = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_SEQUENCE\"}", "ROUTER");
        assertEquals("PRIOR_SUMMARY_ONLY", sequence.maContextPolicyOrDefault());
        assertTrue(sequence.priorSummaryOn());
        String json = AgentRuntimeConfig.toStorageJson(sequence, "ROUTER");
        assertTrue(json.contains("\"maContextPolicy\":\"PRIOR_SUMMARY_ONLY\""), json);

        AgentRuntimeConfig parallel = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_PARALLEL\",\"maContextPolicy\":\"PRIOR_SUMMARY_ONLY\"}", "ROUTER");
        // 前置摘要只对串行有意义：并行模板保存时收敛为隔离，避免配置与执行口径不一致
        assertEquals("ISOLATED", parallel.maContextPolicyOrDefault());
        assertFalse(parallel.priorSummaryOn());

        AgentRuntimeConfig explicit = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_SEQUENCE\",\"maContextPolicy\":\"ISOLATED\"}", "ROUTER");
        assertEquals("ISOLATED", explicit.maContextPolicyOrDefault());
        // 策略值仍可存隔离，但串行运行时一律交接上一专家结论
        assertTrue(explicit.priorSummaryOn());
    }

    @Test
    void sharedScratchPolicyPersistedAndWinsOverPriorSummary() {
        // 共享草稿区是三值合法策略：任一模板都原样落库。
        // 串行仍交接成文结论，草稿区不能代替这段交接。
        AgentRuntimeConfig serial = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_SEQUENCE\",\"maContextPolicy\":\"SHARED_SCRATCH\"}", "ROUTER");
        assertEquals("SHARED_SCRATCH", serial.maContextPolicyOrDefault());
        assertTrue(serial.scratchOn());
        assertTrue(serial.priorSummaryOn());
        String json = AgentRuntimeConfig.toStorageJson(serial, "ROUTER");
        assertTrue(json.contains("\"maContextPolicy\":\"SHARED_SCRATCH\""), json);

        AgentRuntimeConfig parallel = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_PARALLEL\",\"maContextPolicy\":\"SHARED_SCRATCH\"}", "ROUTER");
        assertEquals("SHARED_SCRATCH", parallel.maContextPolicyOrDefault());
        assertTrue(parallel.scratchOn());

        // 白名单之外的上下文策略仍收敛为隔离（并行）/ 前置摘要（串行）
        AgentRuntimeConfig unknown = AgentRuntimeConfig.parse(
                "{\"maContextPolicy\":\"NOPE\"}", "ROUTER");
        assertEquals("ISOLATED", unknown.maContextPolicyOrDefault());
        assertFalse(unknown.scratchOn());
        assertEquals("PRIOR_SUMMARY_ONLY", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_SEQUENCE\",\"maContextPolicy\":\"NOPE\"}", "ROUTER")
                .maContextPolicyOrDefault());

        // 未选策略时默认仍是「隔离 / 串行前置摘要」，共享草稿必须显式开启
        assertFalse(AgentRuntimeConfig.parse(null, "ROUTER").scratchOn());
        assertFalse(AgentRuntimeConfig.defaultsFor("ROUTER").scratchOn());
    }

    @Test
    void contextPolicyAlwaysIsolatedAndParentHistoryDefaultsOn() {        // 白名单之外的上下文策略仍收敛；单意图继承父历史默认开（与现网一致）
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(
                "{\"maContextPolicy\":\"SHARED_SCRATCH_TYPO\"}", "ROUTER");
        assertEquals("ISOLATED", config.maContextPolicyOrDefault());
        assertTrue(config.allowParentHistoryWhenSingleOn());
        String json = AgentRuntimeConfig.toStorageJson(config, "ROUTER");
        assertTrue(json.contains("\"maContextPolicy\":\"ISOLATED\""), json);

        AgentRuntimeConfig off = AgentRuntimeConfig.parse(
                "{\"maAllowParentHistoryWhenSingle\":false}", "ROUTER");
        assertFalse(off.allowParentHistoryWhenSingleOn());
    }

    @Test
    void childRunModesDefaultToReactOnly() {
        // 子 P&E 是显式能力：缺省 / 空数组 / 空串都必须回落「仅 REACT」，即刀3 前行为
        assertEquals(java.util.List.of("REACT"),
                AgentRuntimeConfig.parse(null, "ROUTER").childRunModesAllowedOrDefault());
        assertEquals(java.util.List.of("REACT"),
                AgentRuntimeConfig.parse("{\"maChildRunModesAllowed\":[]}", "ROUTER")
                        .childRunModesAllowedOrDefault());
        assertFalse(AgentRuntimeConfig.defaultsFor("ROUTER").allowsPlanChild());
        assertTrue(AgentRuntimeConfig.defaultsFor("ROUTER").allowsChildRunMode("REACT"));
    }

    @Test
    void childRunModesPlanAllowedOnlyWhenExplicit() {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(
                "{\"maChildRunModesAllowed\":[\"REACT\",\"PLAN_AND_EXECUTE\"]}", "ROUTER");
        assertTrue(config.allowsPlanChild());
        assertEquals(java.util.List.of("REACT", "PLAN_AND_EXECUTE"), config.childRunModesAllowedOrDefault());
        // 顺序稳定：即使 PROE 写在前，落库读出来也是 REACT 在前，便于前端/校验直接比较
        assertEquals(java.util.List.of("REACT", "PLAN_AND_EXECUTE"),
                AgentRuntimeConfig.parse(
                        "{\"maChildRunModesAllowed\":[\"PLAN_AND_EXECUTE\"]}", "ROUTER")
                        .childRunModesAllowedOrDefault());
    }

    @Test
    void childRunModesDropRouterAndUnknownValues() {
        // ROUTER 恒非法（刀3 已禁子 ROUTER，这里是配置层兜底）；第四种 run_mode 同样丢弃
        assertEquals(java.util.List.of("REACT"),
                AgentRuntimeConfig.parse(
                        "{\"maChildRunModesAllowed\":[\"ROUTER\",\"CRITIC\",\"react\"]}", "ROUTER")
                        .childRunModesAllowedOrDefault());
        assertFalse(AgentRuntimeConfig.parse(
                "{\"maChildRunModesAllowed\":[\"ROUTER\"]}", "ROUTER").allowsPlanChild());
    }

    @Test
    void childRunModesTolerateCommaString() {
        // 手改过 JSON 的历史脏值：数组里只有一项逗号串，也要拆开
        assertEquals(java.util.List.of("REACT", "PLAN_AND_EXECUTE"),
                AgentRuntimeConfig.parse(
                        "{\"maChildRunModesAllowed\":[\"REACT,PLAN_AND_EXECUTE\"]}", "ROUTER")
                        .childRunModesAllowedOrDefault());
    }

    @Test
    void childRunModesWrittenIntoStorageJson() {
        AgentRuntimeConfig source = new AgentRuntimeConfig();
        source.setMaChildRunModesAllowed(new java.util.ArrayList<>(
                java.util.List.of("PLAN_AND_EXECUTE", "REACT")));
        String json = AgentRuntimeConfig.toStorageJson(source, "ROUTER");
        assertTrue(json.contains("\"maChildRunModesAllowed\":[\"REACT\",\"PLAN_AND_EXECUTE\"]"), json);
    }

    @Test
    void criticLoopTemplatePersistedWithDefaults() {
        // 评审环是第四种可持久模板：显式选了必须原样保留，默认配置回落到路由并行
        assertEquals("CRITIC_LOOP", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"CRITIC_LOOP\"}", "ROUTER").maTemplateOrDefault());
        assertTrue(AgentRuntimeConfig.parse("{\"maTemplate\":\"CRITIC_LOOP\"}", "ROUTER").isCriticLoop());
        assertFalse(AgentRuntimeConfig.parse("{\"maTemplate\":\"FIXED_PARALLEL\"}", "ROUTER").isCriticLoop());
        // 评审环不是固定模板：不能落进 FIXED_* 的执行分支
        assertFalse(AgentRuntimeConfig.parse("{\"maTemplate\":\"CRITIC_LOOP\"}", "ROUTER").isFixedTemplate());
        // 缺省轮次 1、缺省未选评审者
        AgentRuntimeConfig defaults = AgentRuntimeConfig.defaultsFor("ROUTER");
        assertEquals(1, defaults.criticMaxRoundsOrDefault());
        assertEquals(null, defaults.getMaCriticAgentId());
    }

    @Test
    void criticRoundsClampedToOneToThree() {
        assertEquals(1, AgentRuntimeConfig.parse("{\"maCriticMaxRounds\":0}", "ROUTER")
                .criticMaxRoundsOrDefault());
        assertEquals(1, AgentRuntimeConfig.parse("{\"maCriticMaxRounds\":-5}", "ROUTER")
                .criticMaxRoundsOrDefault());
        assertEquals(2, AgentRuntimeConfig.parse("{\"maCriticMaxRounds\":2}", "ROUTER")
                .criticMaxRoundsOrDefault());
        assertEquals(3, AgentRuntimeConfig.parse("{\"maCriticMaxRounds\":99}", "ROUTER")
                .criticMaxRoundsOrDefault());
        assertEquals(1, AgentRuntimeConfig.parse("{\"maCriticMaxRounds\":null}", "ROUTER")
                .criticMaxRoundsOrDefault());
    }

    @Test
    void criticAgentIdKeptAcrossTemplates() {
        // 切回非评审环也保留评审者 id：页面上再切回来能恢复上次选择，执行侧只在 CRITIC_LOOP 读取
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_PARALLEL\",\"maCriticAgentId\":42,\"maCriticMaxRounds\":2}", "ROUTER");
        assertEquals(Long.valueOf(42L), config.getMaCriticAgentId());
        String json = AgentRuntimeConfig.toStorageJson(config, "ROUTER");
        assertTrue(json.contains("\"maCriticAgentId\":42"), json);
        assertTrue(json.contains("\"maCriticMaxRounds\":2"), json);
    }

    @Test
    void criticValuesWrittenIntoStorageJson() {
        AgentRuntimeConfig source = new AgentRuntimeConfig();
        source.setMaTemplate("CRITIC_LOOP");
        source.setMaCriticAgentId(7L);
        source.setMaCriticMaxRounds(3);
        String json = AgentRuntimeConfig.toStorageJson(source, "ROUTER");
        assertTrue(json.contains("\"maTemplate\":\"CRITIC_LOOP\""), json);
        assertTrue(json.contains("\"maCriticMaxRounds\":3"), json);
        assertTrue(json.contains("\"maCriticAgentId\":7"), json);
    }

    @Test
    void routerThenSequenceTemplatePersistedWithPriorSummaryDefault() {
        // 第五种模板：显式选了必须原样保留，且默认上下文策略＝前置摘要（与固定串行同口径）
        AgentRuntimeConfig sequence = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\"}", "ROUTER");
        assertEquals("ROUTER_THEN_SEQUENCE", sequence.maTemplateOrDefault());
        assertTrue(sequence.isRouterThenSequence());
        assertTrue(sequence.isSerialTemplate());
        // 它不是固定模板：不能落进 FIXED_* 的执行分支（那种分支会跳过判路由）
        assertFalse(sequence.isFixedTemplate());
        assertEquals("PRIOR_SUMMARY_ONLY", sequence.maContextPolicyOrDefault());
        assertTrue(sequence.priorSummaryOn());

        String json = AgentRuntimeConfig.toStorageJson(sequence, "ROUTER");
        assertTrue(json.contains("\"maTemplate\":\"ROUTER_THEN_SEQUENCE\""), json);
        assertTrue(json.contains("\"maContextPolicy\":\"PRIOR_SUMMARY_ONLY\""), json);
    }

    @Test
    void routerThenSequenceAllowsExplicitIsolation() {
        // 用户可显式选隔离并保存；串行执行仍会把上一专家结论交给下一位
        AgentRuntimeConfig isolated = AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"maContextPolicy\":\"ISOLATED\"}", "ROUTER");
        assertEquals("ISOLATED", isolated.maContextPolicyOrDefault());
        assertTrue(isolated.priorSummaryOn());

        // 反向：并行 / 评审环把 PRIOR_SUMMARY_ONLY 收敛回隔离，避免配置与执行口径不一致
        assertFalse(AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"ROUTER_PARALLEL\",\"maContextPolicy\":\"PRIOR_SUMMARY_ONLY\"}", "ROUTER")
                .priorSummaryOn());
        assertFalse(AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"CRITIC_LOOP\",\"maContextPolicy\":\"PRIOR_SUMMARY_ONLY\"}", "ROUTER")
                .priorSummaryOn());
    }

    @Test
    void sixTemplateWhitelistRejectsUnknown() {
        assertTrue(AgentRuntimeConfig.validTemplate("ROUTER_THEN_SEQUENCE"));
        assertTrue(AgentRuntimeConfig.validTemplate("CRITIC_LOOP"));
        // 刀4：COMPETE 已入白名单，方案 / Agent 配置可持久
        assertTrue(AgentRuntimeConfig.validTemplate("COMPETE"));
        assertFalse(AgentRuntimeConfig.validTemplate("SHARED_SCRATCH"));
        // 非法值仍回落路由并行，不能把用户的选择洗成另一种「像是有判路由」的模板
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"DEBATE\"}", "ROUTER").maTemplateOrDefault());
    }

    @Test
    void competeStrategyDefaultsAndFallsBack() {
        // 未配置 → 默认评选模型选优
        assertEquals(AgentRuntimeConfig.COMPETE_STRATEGY_JUDGE_PICK, AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"COMPETE\"}", "ROUTER").competeStrategyOrDefault());
        // 非法策略 → 回落默认，不把未知值带进执行
        assertEquals(AgentRuntimeConfig.COMPETE_STRATEGY_JUDGE_PICK, AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"DEBATE\"}", "ROUTER").competeStrategyOrDefault());
        // 显式 FIRST_SUCCESS 原样保留
        assertEquals(AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS, AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"FIRST_SUCCESS\"}", "ROUTER")
                .competeStrategyOrDefault());
    }

    @Test
    void maTemplatesCollapseToTwoModesWithoutTouchingEntry() {
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.canonicalMaTemplate("ROUTER_PARALLEL"));
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.canonicalMaTemplate("FIXED_PARALLEL"));
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.canonicalMaTemplate("COMPETE"));
        assertEquals("ROUTER_PARALLEL", AgentRuntimeConfig.canonicalMaTemplate("CRITIC_LOOP"));
        assertEquals("ROUTER_THEN_SEQUENCE", AgentRuntimeConfig.canonicalMaTemplate("FIXED_SEQUENCE"));
        assertEquals("ROUTER_THEN_SEQUENCE", AgentRuntimeConfig.canonicalMaTemplate("ROUTER_THEN_SEQUENCE"));
        assertEquals("ENTRY_ROUTER", AgentRuntimeConfig.canonicalMaTemplate("ENTRY_ROUTER"));
        assertNull(AgentRuntimeConfig.canonicalMaTemplate("DEBATE"));
        // Agent 配置解析仍保留旧码，避免把单兵编排一起洗掉
        assertEquals("FIXED_SEQUENCE", AgentRuntimeConfig.parse(
                "{\"maTemplate\":\"FIXED_SEQUENCE\"}", "ROUTER").maTemplateOrDefault());
    }
}
