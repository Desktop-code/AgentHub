package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由后串行（ROUTER_THEN_SEQUENCE）真执行窄测。所属域：runtime。
 *
 * <p>只 mock 外部协作者（库、装配、ReAct、记忆、限制），跑真实分支：
 * JUDGE 契约不变 / 执行顺序＝判定数组序 / 前置摘要硬顶复用 / 超上限跳过 /
 * 判路由失败即整次 FAIL / 部分失败继续 / 与并行模板的回归对照。
 * 不起 Spring，不连库，不出站模型。
 */
class RouterThenSequenceRouterTrialExecutorTest {

    private AgentRouteRepository routes;
    private AgentDefRepository agents;
    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private SystemPromptAssembler prompts;
    private RuntimeLimitResolver limitResolver;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private ChildPlanApprovalRegistry childApprovals;
    private ThreadPoolTaskExecutor pool;
    private RouterTrialExecutor executor;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        routes = mock(AgentRouteRepository.class);
        agents = mock(AgentDefRepository.class);
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        prompts = mock(SystemPromptAssembler.class);
        limitResolver = mock(RuntimeLimitResolver.class);
        memory = mock(TrialMemoryCoordinator.class);
        planRunner = mock(PlanTrialExecutor.class);
        childApprovals = new ChildPlanApprovalRegistry();
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(8);
        pool.initialize();
        executor = new RouterTrialExecutor(routes, agents, loader, react, prompts, limitResolver, memory,
                planRunner, childApprovals, pool);

        when(limitResolver.resolve(any())).thenReturn(limits);
        when(prompts.build(any(), any())).thenReturn("");
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * 模型先选出人，再按执行顺序接力。sort_order 相同时按 route_key，不跟判定数组把 b 排到 a 前面。
     * 后一步吃「本步子问 + 前置成文结论」。
     */
    @Test
    void executionOrderDrivesSerialHandoffNotJudgeArray() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"answerMode\":\"CONCAT\","
                + "\"multiIntentEnabled\":true,\"maxRouteChildren\":3}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        // 两条执行顺序相同；判路由把 b 放在数组前面。接力必须仍是 a → b
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"b\",\"sub_query\":\"子问B\"},{\"route_key\":\"a\",\"sub_query\":\"子问A\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("第一步答案"), turn("第二步答案"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 41L);

        // 判路由必调且只调一次：本模板不允许「跳过 JUDGE」
        verify(react, times(1)).judge(any(), any(), any(), any());
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), texts.capture(), anyLong(), anyLong(), any());

        // 第一步＝子问A（执行顺序）；第二步＝子问B + 前置结论（含第一步终答）
        assertEquals("子问A", texts.getAllValues().get(0));
        String second = texts.getAllValues().get(1);
        assertTrue(second.contains("子问B"), second);
        assertTrue(second.contains("【前置专家结论】"), second);
        assertTrue(second.contains("第一步答案"), second);
        // 本步问题基座是判路由切好的子问，不是整段合问
        assertTrue(second.contains("本轮问题：\n子问B"), second);
        assertTrue(!second.contains("本轮问题：\n用户原问"), second);

        // 终答按执行顺序拼接：先 a 后 b
        String answer = routed.turn().getAssistantText();
        assertTrue(answer.indexOf("第一步答案") < answer.indexOf("第二步答案"), answer);
        assertEquals(2, routed.segments().size());
    }

    /**
     * 显式 ISOLATED 也不能切断串行交接：第二步仍能看到第一步的成文结论。
     */
    @Test
    void explicitIsolatedStillHandsOffPreviousConclusion() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"answerMode\":\"CONCAT\","
                + "\"maContextPolicy\":\"ISOLATED\",\"multiIntentEnabled\":true}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},{\"route_key\":\"b\",\"sub_query\":\"子问B\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("甲的结论"), turn("乙"));

        executor.execute(parent, "用户原问", 7L, 42L);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), texts.capture(), anyLong(), anyLong(), any());
        assertEquals("子问A", texts.getAllValues().get(0));
        String second = texts.getAllValues().get(1);
        assertTrue(second.contains("子问B"), second);
        assertTrue(second.contains("【前置专家结论】"), second);
        assertTrue(second.contains("甲的结论"), second);
        // 交接的是成文结论，不带 route_key
        assertTrue(!second.contains("route_key"), second);
    }

    /**
     * 判定条数 > maxRouteChildren：只跑判定序前 N，其余标「已跳过」并进终答。
     */
    @Test
    void excessJudgedStepsAreSkippedByQuota() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"answerMode\":\"CONCAT\","
                + "\"multiIntentEnabled\":true,\"maxRouteChildren\":2}");
        givenChildren(parent, "a", "b", "c");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        // 模型忽略「最多 2 项」的约束，回了 3 项：第 3 项必须标已跳过，不能静默消失
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},"
                        + "{\"route_key\":\"b\",\"sub_query\":\"子问B\"},"
                        + "{\"route_key\":\"c\",\"sub_query\":\"子问C\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("甲"), turn("乙"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 43L);

        verify(react, times(2)).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertTrue(routed.turn().getAssistantText().contains("（已跳过）"),
                routed.turn().getAssistantText());
        assertEquals(3, routed.segments().size());
    }

    /**
     * 部分失败：失败步脱敏占位，后续步仍继续（与固定串行同纪律）。
     */
    @Test
    void partialFailureContinuesToNextStep() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"answerMode\":\"CONCAT\","
                + "\"multiIntentEnabled\":true}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},{\"route_key\":\"b\",\"sub_query\":\"子问B\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("模型炸了"))
                .thenReturn(turn("第二步答案"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 44L);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), texts.capture(), anyLong(), anyLong(), any());
        // 失败步的异常原文不进下一步，只给中文占位
        String second = texts.getAllValues().get(1);
        assertTrue(second.contains("（该步未完成）"), second);
        assertTrue(!second.contains("模型炸了"), second);

        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("（执行失败）"), answer);
        assertTrue(answer.contains("第二步答案"), answer);
    }

    /**
     * 判路由失败：整次 FAIL，绝不拿第一条候选兜底。
     */
    @Test
    void judgeEmptyFailsInsteadOfFallingBack() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"multiIntentEnabled\":true}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn("[]");

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 45L));
        assertTrue(failure.getMessage().contains("判路由失败"), failure.getMessage());
        // 没有可用判定就不该有任何子被拉起来跑
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /**
     * 父 Agent 没绑路由模型：判路由无从谈起，整次 FAIL 并给可操作中文。
     */
    @Test
    void missingRouterModelFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\"}");
        givenChildren(parent, "a");
        when(loader.findRouterModel(1L)).thenReturn(Optional.empty());

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 46L));
        assertTrue(failure.getMessage().contains("未绑定"), failure.getMessage());
        assertTrue(failure.getMessage().contains("判路由"), failure.getMessage());
    }

    /**
     * 零可用候选：本模板没有「无判路由兜底」，必须中文 FAIL。
     */
    @Test
    void zeroCandidatesFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\"}");
        when(routes.listByParentId(1L)).thenReturn(List.of());

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 47L));
        assertTrue(failure.getMessage().contains("路由后串行没有可用的子 Agent"), failure.getMessage());
    }

    /**
     * 回归：ROUTER_PARALLEL 仍按各自子问并行派发，不会被串行摘要污染。
     */
    @Test
    void routerParallelRegressionKeepsIsolatedSubQueries() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_PARALLEL\",\"multiIntentEnabled\":true,"
                + "\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},{\"route_key\":\"b\",\"sub_query\":\"子问B\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("甲"), turn("乙"));

        executor.execute(parent, "用户原问", 7L, 48L);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), texts.capture(), anyLong(), anyLong(), any());
        // 并行路径的子只吃自己的 sub_query（并发消费顺序不定，这里按集合比对），
        // 且绝不出现串行摘要区——证明新模板没有把 ROUTER_PARALLEL 改坏
        List<String> sent = new ArrayList<>(texts.getAllValues());
        sent.sort(String::compareTo);
        assertEquals(List.of("子问A", "子问B"), sent);
        assertTrue(texts.getAllValues().stream().noneMatch(text -> text.contains("【前置专家结论】")));
    }

    /**
     * 回归：FIXED_SEQUENCE 仍跳过判路由（模板分支没有被新增模板带偏）。
     */
    @Test
    void fixedSequenceRegressionStillSkipsJudge() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_SEQUENCE\",\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答"));

        executor.execute(parent, "用户原问", 7L, 49L);

        verify(react, never()).judge(any(), any(), any(), any());
        verify(loader, never()).findRouterModel(any());
    }

    /**
     * 方案内层职责覆盖：问句同时打中制度检索与报告撰写两位，两位都要派出，且不再问判路由模型。
     *
     * <p>复现现网那条会丢专家的问句。判路由模型在这里被配成只会输出一位——
     * 如果实现仍靠模型决定条数，这条断言就会失败。
     */
    @Test
    void coveredMembersDispatchBothPolicyAndWriterWithoutJudge() {
        AgentDef parent = null;
        MaRunSpec spec = coveringSpec("ROUTER_THEN_SEQUENCE");
        // 模型只会回一位；覆盖命中就不该走到它
        String question = "请综合台根据公司制度整理知识要点，并起草一份报告";
        when(react.judge(any(), any(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"MA_Report_Writer\",\"sub_query\":\"只写报告\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("甲"), turn("乙"));

        executor.execute(parent, spec, question, question, 7L, 51L, null);

        // 命中两位就派两位，顺序按 sortOrder：制度知识专家(30) 在报告撰写专家(40) 之前
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), texts.capture(), anyLong(), anyLong(), any());
        verify(react, never()).judge(any(), any(), any(), any());
        assertTrue(texts.getAllValues().get(0).contains("公司制度"), texts.getAllValues().get(0));
        // 第二位拿到的是同一条整句 + 前置结论，不是模型编的子问
        String second = texts.getAllValues().get(1);
        assertTrue(second.contains("公司制度"), second);
        assertTrue(second.contains("【前置专家结论】"), second);
        assertTrue(!second.contains("只写报告"), second);
    }

    /**
     * 方案内层职责覆盖：单问只打中一位时也只派这一位，不把无关成员带上，也不再问模型。
     */
    @Test
    void coveredMembersDispatchSingleWithoutJudge() {
        AgentDef parent = null;
        MaRunSpec spec = coveringSpec("ROUTER_THEN_SEQUENCE");
        String question = "查询下退款制度";
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答"));

        executor.execute(parent, spec, question, question, 7L, 52L, null);

        // 只剩制度知识专家一位：报告撰写专家、退款客服专家都不该被带上
        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());
        verify(react, never()).judge(any(), any(), any(), any());
    }

    /**
     * 方案内层职责覆盖：一位都没打中时仍走现网判定模型，行为不变。
     */
    @Test
    void uncoveredQuestionStillCallsJudge() {
        AgentDef parent = null;
        MaRunSpec spec = coveringSpec("ROUTER_THEN_SEQUENCE");
        String question = "你好";
        when(react.judge(any(), any(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"MA_Report_Writer\",\"sub_query\":\"寒暄兜底\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答"));

        executor.execute(parent, spec, question, question, 7L, 53L, null);

        verify(react, times(1)).judge(any(), any(), any(), any());
        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /**
     * 方案内层覆盖只认子 Agent 成员：方案 / 工作流目标不参与名称匹配，仍交给模型判断。
     */
    @Test
    void coverageIgnoresDelegatedTargets() {
        AgentDef parent = null;
        MaRunSpec spec = coveringSpec("ROUTER_THEN_SEQUENCE");
        String question = "查询下退款制度";
        // 目标方案外壳即使名字里带「退款」，也不能被覆盖判定当成一名成员
        AgentDef shell = new AgentDef();
        shell.setName("退款制度查询方案");
        shell.setCode("refund_scheme");
        shell.setStatus(AgentStatus.ENABLED);
        MaRunSpec target = new MaRunSpec(99L, "refund_scheme", "退款制度查询方案", "说明",
                AgentRuntimeConfig.TEMPLATE_ROUTER_PARALLEL,
                AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name()), List.of(), null, null, false);
        MaCandidate maRow = new MaCandidate();
        maRow.setId(9999L);
        maRow.setMaId(spec.maId());
        maRow.setTargetType(MaCandidate.TARGET_MA);
        maRow.setMaDefId(99L);
        maRow.setRouteKey("refund_scheme");
        maRow.setEnabled(AgentStatus.ENABLED);
        List<MaCandidate> rows = new ArrayList<>(spec.candidates());
        rows.add(maRow);
        MaRunSpec withTarget = new MaRunSpec(spec.maId(), spec.code(), spec.name(), spec.description(),
                spec.template(), spec.runtime(), rows, null, spec.judgeModelInstanceId(), false,
                java.util.Map.of(99L, target), java.util.Map.of());
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答"));

        executor.execute(parent, withTarget, question, question, 7L, 54L, null);

        // 只有制度知识专家被派出：目标方案没有被覆盖判定带上
        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /**
     * 造一个可跑「综合台」口径的方案：三位真成员。
     *
     * <p>成员的名称与简介取现网 agent_def 原文，覆盖判定才有意义。
     *
     * @param template 模板码
     * @return 方案执行来源
     */
    private MaRunSpec coveringSpec(String template) {
        AgentDef policy = agent(34L, "制度知识专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, null);
        policy.setDescription("制度与流程检索问答");
        AgentDef writer = agent(35L, "报告撰写专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, null);
        writer.setDescription("整理材料为结构化报告并可落盘");
        AgentDef refund = agent(33L, "退款客服专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, null);
        refund.setDescription("退款售后政策问答与处理建议");
        when(agents.findById(34L)).thenReturn(Optional.of(policy));
        when(agents.findById(35L)).thenReturn(Optional.of(writer));
        when(agents.findById(33L)).thenReturn(Optional.of(refund));

        List<MaCandidate> rows = List.of(
                specCandidate(33L, "MA_CS_Refund", 10),
                specCandidate(34L, "MA_KB_Policy", 30),
                specCandidate(35L, "MA_Report_Writer", 40));
        return new MaRunSpec(1L, "ma_from_agent_001", "综合台", "场景分流", template,
                AgentRuntimeConfig.parse("{\"maTemplate\":\"" + template + "\",\"multiIntentEnabled\":true,"
                                + "\"maxRouteChildren\":5}", AgentRunMode.ROUTER.name()),
                rows, null, 9L, false);
    }

    /**
     * 方案候选行。
     *
     * @param agentId  成员主键
     * @param routeKey 路由键
     * @param sortNo   方案里的执行顺序
     * @return 启用中的单兵候选
     */
    private MaCandidate specCandidate(Long agentId, String routeKey, int sortNo) {
        MaCandidate row = new MaCandidate();
        row.setId(agentId + 5000);
        row.setMaId(1L);
        row.setAgentId(agentId);
        row.setTargetType(MaCandidate.TARGET_AGENT);
        row.setRouteKey(routeKey);
        row.setSortNo(sortNo);
        row.setEnabled(AgentStatus.ENABLED);
        return row;
    }

    /** 造父 Agent（ROUTER + 指定 runtime_config）。 */
    private AgentDef parent(String runtimeJson) {
        return agent(1L, "父路由器", AgentRunMode.ROUTER.name(), AgentStatus.ENABLED, runtimeJson);
    }

    /** 造父 + N 个启用的 REACT 子，并挂好 route_key=a/b/c…。 */
    private void givenChildren(AgentDef parent, String... keys) {
        List<AgentRoute> rows = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            long childId = 10L + i;
            rows.add(route(keys[i], childId));
            when(agents.findById(childId)).thenReturn(Optional.of(
                    agent(childId, "子" + keys[i].toUpperCase(), AgentRunMode.REACT.name(), AgentStatus.ENABLED, null)));
        }
        when(routes.listByParentId(parent.getId())).thenReturn(rows);
    }

    private AgentRoute route(String key, Long childId) {
        AgentRoute route = new AgentRoute();
        route.setId(childId + 100);
        route.setParentAgentId(1L);
        route.setChildAgentId(childId);
        route.setRouteKey(key);
        route.setIntent("意图" + key);
        route.setSortOrder(10);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    private AgentDef agent(Long id, String name, String runMode, int status, String runtimeJson) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        agent.setRuntimeConfigJson(runtimeJson);
        return agent;
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
