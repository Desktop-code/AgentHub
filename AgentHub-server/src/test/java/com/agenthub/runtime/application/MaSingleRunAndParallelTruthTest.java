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
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「同一轮只跑一份方案、模式与角标一致、不用 CONCAT 冒充汇总」的运行链路回归。所属域：runtime。
 *
 * <p>针对用户手点的三个现象各钉一条：
 * <ol>
 *   <li><b>同一轮两条综合台</b>：历史迁移把旧 ROUTER 迁成 `ma_from_agent_{id}`，
 *       它若被当成子候选，同一轮就会再跑一份同名方案。装载候选时必须挡掉「自己的迁移副本」。</li>
 *   <li><b>角标写并行、实际串行</b>：目标方案内多子执行曾被改成「当前线程按序逐个跑」，
 *       把并行语义悄悄改成串行。本测试用一个「必须两路同时在跑」的闸门证明它真的并行。</li>
 *   <li><b>CONCAT 冒充汇总</b>：汇总失败降级拼接时不能再打「方案结论」标记，
 *       否则消息流会多出一条内容其实是拼接稿的汇总条。</li>
 * </ol>
 *
 * 不起 Spring、不连库、不出站真实模型；时间断言只用「是否同时进入」而不是耗时。
 */
class MaSingleRunAndParallelTruthTest {

    private AgentDefRepository agents;
    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private ThreadPoolTaskExecutor pool;
    private RouterTrialExecutor executor;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        AgentRouteRepository routes = mock(AgentRouteRepository.class);
        agents = mock(AgentDefRepository.class);
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        RuntimeLimitResolver limitResolver = mock(RuntimeLimitResolver.class);
        TrialMemoryCoordinator memory = mock(TrialMemoryCoordinator.class);
        SystemPromptAssembler prompts = mock(SystemPromptAssembler.class);
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(4);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(16);
        pool.initialize();
        executor = new RouterTrialExecutor(routes, agents, loader, react, prompts, limitResolver, memory,
                mock(PlanTrialExecutor.class), new ChildPlanApprovalRegistry(), pool);

        when(limitResolver.resolve(any())).thenReturn(limits);
        when(prompts.build(any(), any())).thenReturn("");
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
        when(loader.findRouterModel(anyLong())).thenReturn(Optional.empty());
        // 批量取子：按已登记集合解析（注册一次，避免桩里再嵌套 when）
        when(agents.findByIds(any())).thenAnswer(invocation -> resolveRegistered(invocation.getArgument(0)));
        // 候选里的子是按 id 逐个取的：统一走已登记集合，避免逐条 when 互相覆盖
        stubAllFindById();
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * 自己的迁移副本不能当子候选：同一轮只跑一份方案。
     *
     * <p>造一个方案 `企业综合台`，池里既有两位专家、又误加了 `ma_from_agent_99`（名称「企业综合台（迁移）」）。
     * 期望：只跑两位专家，迁移副本按「已在运行同名方案」跳过，绝不进入第二层执行。
     */
    @Test
    void migratedWrapperOfItselfIsNotDispatchedAgain() {
        long policyId = 41L;
        long reportId = 42L;
        long wrapperMaId = 990L;
        givenAgent(policyId, "制度知识专家");
        givenAgent(reportId, "报告撰写专家");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));

        MaRunSpec wrapper = new MaRunSpec(wrapperMaId, "ma_from_agent_99", "企业综合台（迁移）",
                "迁移副本", "ROUTER_PARALLEL", runtime("ROUTER_PARALLEL"),
                List.of(), null, null, false);
        MaRunSpec scheme = new MaRunSpec(5L, "enterprise", "企业综合台", "制度检索；报告起草",
                "FIXED_PARALLEL", runtime("FIXED_PARALLEL"),
                List.of(candidate(policyId, "policy", 1, 1), candidate(reportId, "report", 2, 1),
                        maCandidate(wrapperMaId, "enterprise_copy", 3)),
                null, null, false,
                java.util.Map.of(wrapperMaId, wrapper), java.util.Map.of());

        RouterTrialExecutor.RoutedTurn routed = executor.execute(null, scheme, "用户原问", "用户原问",
                7L, 61L, null);

        // 两位专家各跑一次；迁移副本既不执行，也不在时间线上留一行「已跳过 企业综合台」
        assertEquals(2, routed.segments().size(),
                "同一轮只能有两位子专家：" + routed.segments());
        assertFalse(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null
                                && segment.routeLine().contains("迁移")),
                "迁移副本不该出现在时间线上：" + routed.segments());
        assertFalse(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null
                                && segment.routeLine().contains("已跳过")),
                "迁移副本应静默跳过，不画「已跳过」行：" + routed.segments());
    }

    /**
     * 「并行」必须真的并行：两位子必须同时在跑。
     *
     * <p>用 FIXED_PARALLEL 直接检验并行机制本身（不掺判路由）：两道闸门证明
     * A 进入后等 B 也进入才继续；若实现是串行，A 会一直等到超时。
     * 这也是「角标写并行、实际却串行」这一现象最直接的回归点。
     */
    @Test
    void fixedParallelReallyRunsChildrenConcurrently() throws Exception {
        long firstId = 41L;
        long secondId = 42L;
        givenAgent(firstId, "制度知识专家");
        givenAgent(secondId, "报告撰写专家");

        CountDownLatch bothInside = new CountDownLatch(2);
        AtomicInteger observedPeak = new AtomicInteger();
        AtomicInteger inside = new AtomicInteger();
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    int now = inside.incrementAndGet();
                    observedPeak.accumulateAndGet(now, Math::max);
                    bothInside.countDown();
                    // 等另一位也进来：串行实现下这里会超时，从而暴露「假并行」
                    bothInside.await(5, TimeUnit.SECONDS);
                    inside.decrementAndGet();
                    return turn("答" + now);
                });

        MaRunSpec scheme = new MaRunSpec(5L, "enterprise", "企业综合台", "制度检索；报告起草",
                "FIXED_PARALLEL", runtime("FIXED_PARALLEL"),
                List.of(candidate(firstId, "policy", 1, 1), candidate(secondId, "report", 2, 1)),
                null, null, false);
        assertEquals(2, scheme.candidates().size(), "测试数据本身应带 2 位候选");
        // 桩自检：两条候选的子 Agent 都必须能取到，否则「只有 1 段」是测试数据问题而非实现问题
        assertTrue(agents.findById(firstId).isPresent(), "第一位子 Agent 桩缺失");
        assertTrue(agents.findById(secondId).isPresent(), "第二位子 Agent 桩缺失");
        assertEquals(3, scheme.runtime().maxRouteChildrenOrDefault(), "并行上限应保留默认 3");

        RouterTrialExecutor.RoutedTurn routed =
                executor.execute(null, scheme, "先整理条款再起草请示", "先整理条款再起草请示", 7L, 62L, null);

        assertEquals(2, observedPeak.get(),
                "两位子专家的执行区间必须重叠（角标写并行就要真并行）：段数=" + routed.segments().size());
    }

    /**
     * 并行子不接收彼此结论：各自只吃自己的输入，不做前置交接。
     */
    @Test
    void parallelChildrenDoNotInheritEachOtherConclusion() {
        long firstId = 41L;
        long secondId = 42L;
        givenAgent(firstId, "制度知识专家");
        givenAgent(secondId, "报告撰写专家");
        List<String> inputs = java.util.Collections.synchronizedList(new ArrayList<>());
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    inputs.add(invocation.getArgument(1));
                    return turn("答");
                });

        MaRunSpec scheme = new MaRunSpec(5L, "enterprise", "企业综合台", "制度检索；报告起草",
                "FIXED_PARALLEL", runtime("FIXED_PARALLEL"),
                List.of(candidate(firstId, "policy", 1, 1), candidate(secondId, "report", 2, 1)),
                null, null, false);
        executor.execute(null, scheme, "先整理条款再起草请示", "先整理条款再起草请示", 7L, 63L, null);

        assertEquals(2, inputs.size());
        // 并行模板不做前置交接：两路输入里都不该出现对方的成文结论标记
        assertTrue(inputs.stream().noneMatch(text -> text.contains("【前置专家结论】")), inputs.toString());
    }

    /**
     * 路由后串行必须交接成文结论：后一位的输入带前一位的结论。
     */
    @Test
    void routerThenSequenceHandsOverPriorConclusion() {
        long firstId = 41L;
        long secondId = 42L;
        givenAgent(firstId, "制度知识专家");
        givenAgent(secondId, "报告撰写专家");
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"policy\",\"sub_query\":\"整理条款\"},"
                        + "{\"route_key\":\"report\",\"sub_query\":\"起草请示\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(turn("制度要点：年假 5 天"))
                .thenReturn(turn("请示已起草"));

        // 判路由模型由方案自带，避免依赖绑根 Agent 的 ROUTER 槽
        MaRunSpec scheme = new MaRunSpec(5L, "enterprise", "企业综合台", "制度检索；报告起草",
                "ROUTER_THEN_SEQUENCE", runtime("ROUTER_THEN_SEQUENCE"),
                List.of(candidate(firstId, "policy", 1, 1), candidate(secondId, "report", 2, 1)),
                null, 9L, false);
        executor.execute(null, scheme, "先整理条款再起草请示", "先整理条款再起草请示", 7L, 64L, null);

        org.mockito.ArgumentCaptor<String> inputs = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(react, org.mockito.Mockito.times(2))
                .execute(any(), inputs.capture(), anyLong(), anyLong(), any());
        String second = inputs.getAllValues().get(1);
        assertTrue(second.contains("【前置专家结论】"), second);
        assertTrue(second.contains("制度要点"), "后一位必须拿到前一位的成文结论：" + second);
    }

    /**
     * CONCAT 降级不能冒充汇总：汇总模型不可用时，不得推「方案结论」标记。
     *
     * <p>否则消息流会在子专家之后多出一条「汇总」，内容其实是拼接的子专家原文。
     */
    @Test
    void concatFallbackDoesNotEmitSchemeConclusionMarker() {
        long firstId = 41L;
        long secondId = 42L;
        givenAgent(firstId, "制度知识专家");
        givenAgent(secondId, "报告撰写专家");
        when(loader.findRouterModel(anyLong())).thenReturn(Optional.of(9L));
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"policy\",\"sub_query\":\"整理条款\"},"
                        + "{\"route_key\":\"report\",\"sub_query\":\"起草请示\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(turn("制度要点"))
                .thenReturn(turn("请示草稿"));
        // 汇总模型直接失败 → 走 CONCAT 降级
        when(react.summarizeStreamed(any(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("汇总模型不可用"));

        List<String> routeLines = java.util.Collections.synchronizedList(new ArrayList<>());
        TrialProgress progress = recordingProgress(routeLines);

        // answerMode=SUMMARIZE：汇总失败会降级为拼接
        AgentRuntimeConfig runtime = runtime("ROUTER_PARALLEL");
        runtime.setAnswerMode("SUMMARIZE");
        MaRunSpec summarizeSpec = new MaRunSpec(5L, "enterprise", "企业综合台", "制度检索；报告起草",
                "ROUTER_PARALLEL", runtime,
                List.of(candidate(firstId, "policy", 1, 1), candidate(secondId, "report", 2, 1)),
                null, 9L, false);
        executor.execute(null, summarizeSpec, "先整理条款再起草请示", "先整理条款再起草请示", 7L, 65L, progress);

        assertFalse(routeLines.stream().anyMatch(line -> line.startsWith("方案结论：")),
                "CONCAT 降级不得打汇总标记：" + routeLines);
        assertTrue(routeLines.stream().anyMatch(line -> line.contains("降级")),
                "降级要有可见说明：" + routeLines);
    }

    /** 只记 route 的进度出口：用来断言「有没有推方案结论标记」。 */
    private TrialProgress recordingProgress(List<String> routeLines) {
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
            }

            @Override
            public void route(String summary) {
                if (summary != null) {
                    routeLines.add(summary);
                }
            }

            @Override
            public void reasoning(String delta) {
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
            }

            @Override
            public void assistant(String delta) {
            }
        };
    }

    /** 已登记的子 Agent：batch findByIds 从这里取，避免在 givenAgent 里再嵌套 when。 */
    private final java.util.Map<Long, AgentDef> registered = new java.util.LinkedHashMap<>();

    /** 造启用 + REACT + 允许被编排的子 Agent。 */
    private void givenAgent(Long id, String name) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode("child_" + id);
        agent.setRunMode(AgentRunMode.REACT.name());
        agent.setStatus(AgentStatus.ENABLED);
        agent.setAllowOrchestrated(1);
        registered.put(id, agent);
    }

    /**
     * 批量取子：按入参 id 从已登记集合里挑。
     *
     * <p>只在 {@link #setUp()} 里注册一次。不能在 {@code givenAgent} 里反复 when 同一个方法：
     * Mockito 会在注册时用 null 试调一次，桩里读到 null 就可能 NPE。
     */
    private List<AgentDef> resolveRegistered(Object ids) {
        if (!(ids instanceof Iterable<?> wanted)) {
            return List.of();
        }
        List<AgentDef> out = new ArrayList<>();
        for (Object raw : wanted) {
            if (raw instanceof Long key && registered.containsKey(key)) {
                out.add(registered.get(key));
            }
        }
        return out;
    }

    /**
     * 候选里的子 Agent 是逐个 {@code findById} 取的，不是批量。
     *
     * <p>这里为每条候选显式返回对应的已登记 Agent；找不到时给空，等价于「子已被删」。
     */
    private void stubAllFindById() {
        when(agents.findById(any())).thenAnswer(invocation -> {
            Object raw = invocation.getArgument(0);
            return raw instanceof Long key ? Optional.ofNullable(registered.get(key)) : Optional.empty();
        });
    }

    private static MaCandidate candidate(Long agentId, String routeKey, int sortNo, int enabled) {
        MaCandidate item = new MaCandidate();
        item.setId(agentId + 1000);
        item.setMaId(5L);
        item.setAgentId(agentId);
        item.setRouteKey(routeKey);
        item.setIntentDesc("意图" + routeKey);
        item.setSortNo(sortNo);
        item.setEnabled(enabled);
        return item;
    }

    private static MaCandidate maCandidate(Long maDefId, String routeKey, int sortNo) {
        MaCandidate item = new MaCandidate();
        item.setId(maDefId + 2000);
        item.setMaId(5L);
        item.setTargetType(MaCandidate.TARGET_MA);
        item.setMaDefId(maDefId);
        item.setRouteKey(routeKey);
        item.setIntentDesc("另一套方案");
        item.setSortNo(sortNo);
        item.setEnabled(1);
        return item;
    }

    private static AgentRuntimeConfig runtime(String template) {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        config.setMaTemplate(template);
        return config;
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
