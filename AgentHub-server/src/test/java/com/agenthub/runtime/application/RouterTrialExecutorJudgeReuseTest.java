package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.prompt.domain.model.PromptLayout;
import com.agenthub.runtime.application.router.Candidate;
import com.agenthub.runtime.application.router.AnswerAggregator;
import com.agenthub.runtime.application.router.RouterJudge;
import com.agenthub.runtime.application.router.RouterJudgePromptFactory;
import com.agenthub.runtime.application.router.EntryQuestionSplitter;
import com.agenthub.runtime.application.router.EntrySchemePreference;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 入口判路由的两处省调用。所属域：runtime。
 * <p>
 * 覆盖命中必须在判定模型之前返回；同一轮多问只装配一次 ROUTER 前缀，各问只换用户文本。
 * 不连库、不打真实模型，只反射调用 {@code choose}。
 */
class RouterTrialExecutorJudgeReuseTest {

    private AgentRouteRepository routes;
    private AgentDefRepository agents;
    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private SystemPromptAssembler prompts;
    private RouterTrialExecutor executor;
    /** 判路由决策已搬到 router 包，本测试反射它的单问入口（选路结果仍不升成公开 API） */
    private RouterJudge judge;

    /**
     * 只装配 {@code choose} 会碰到的依赖。工作流委托留空，本测试不走工作流。
     */
    @BeforeEach
    void setUp() {
        routes = mock(AgentRouteRepository.class);
        agents = mock(AgentDefRepository.class);
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        prompts = mock(SystemPromptAssembler.class);
        executor = new RouterTrialExecutor(routes, agents, loader, react, prompts,
                mock(RuntimeLimitResolver.class), mock(TrialMemoryCoordinator.class),
                mock(PlanTrialExecutor.class), mock(ChildPlanApprovalRegistry.class),
                mock(ThreadPoolTaskExecutor.class));
        // 反射目标随判路由家族搬到 router 包；装配同一份协作者，执行器仍然照常可用
        judge = new RouterJudge(agents, react, new RouterJudgePromptFactory(prompts),
                new AnswerAggregator(loader, prompts, react));
    }

    /**
     * 单问已经能被多项交付覆盖时，不得再调用判定模型，也不得装配 ROUTER。
     */
    @Test
    void coveringHitDoesNotCallJudge() throws Exception {
        String userText = "请制度知识专家核对条款，并请报告撰写专家起草说明";
        // 「并」是同一问里的并列，不能被切成两问，否则覆盖判定不会落在整句上
        assertEquals(1, EntryQuestionSplitter.split(userText, List.of("综合办事处")).size());

        AgentDef policy = member(11L, "制度知识专家");
        AgentDef writer = member(12L, "报告撰写专家");
        when(agents.findByIds(any())).thenReturn(List.of(policy, writer));

        MaCandidate policyRow = memberRow(11L);
        MaCandidate writerRow = memberRow(12L);
        AgentRuntimeConfig runtime = runtime();
        MaRunSpec scheme = new MaRunSpec(501L, "office", "综合办事处", "说明",
                AgentRuntimeConfig.TEMPLATE_ROUTER_PARALLEL, runtime, List.of(policyRow, writerRow),
                null, null, false);
        Candidate schemeCandidate = new Candidate(route("rk_cover_scheme", "zz_cover_intent", 900L),
                member(900L, "综合办事处"), scheme);
        MaRunSpec entry = new MaRunSpec(1L, "entry", "默认接待", AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                runtime, List.of(), null, 88L, false);

        Object choice = choose(entry, userText, List.of(schemeCandidate), runtime);
        List<RoutedIntent> intents = intentsOf(choice);
        assertEquals(1, intents.size());
        assertEquals(EntrySchemePreference.MULTI_REASON, intents.get(0).reason());
        assertEquals("rk_cover_scheme", intents.get(0).candidate().route().getRouteKey());
        verifyNoInteractions(react);
        verifyNoInteractions(prompts);
    }

    /**
     * 同一轮两问都要打模型时，ROUTER 前缀只装配一次，系统提示只差问句，用户侧各送各的问。
     */
    @Test
    void multiQuestionJudgesShareOnePrefix() throws Exception {
        AgentDef alpha = member(21L, "档案员");
        AgentDef beta = member(22L, "台账员");
        Candidate first = new Candidate(route("rk_alpha", "zz_alpha", 21L), alpha);
        Candidate second = new Candidate(route("rk_beta", "zz_beta", 22L), beta);
        List<String> names = List.of(alpha.getName(), beta.getName());
        String raw = "甲问查档案编号九九。另外乙问核对台账编号八八。";
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(raw, names);
        assertEquals(2, questions.size());
        String firstQuestion = questions.get(0).text();
        String secondQuestion = questions.get(1).text();
        assertNotEquals(firstQuestion, secondQuestion);

        List<String> assembledQueries = new ArrayList<>();
        when(prompts.assembleRouter(anyString(), any(AgentRuntimeConfig.class), anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(2);
            assembledQueries.add(query);
            // 把装配入参嵌进正文，才能看出各问是替换槽，还是又装了一遍
            return new SystemPromptAssembler.Assembled("L1与契约|" + query + "|名单", PromptLayout.EMPTY);
        });
        when(react.judge(any(), anyString(), anyString(), any(EffectiveLimits.class))).thenReturn(
                "[{\"route_key\":\"rk_alpha\",\"sub_query\":\"x\",\"reason\":\"职责匹配\"}]");

        AgentRuntimeConfig runtime = runtime();
        MaRunSpec entry = new MaRunSpec(1L, "entry", "默认接待", AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                runtime, List.of(), null, 88L, false);
        choose(entry, raw, List.of(first, second), runtime);

        assertEquals(1, assembledQueries.size(), "同一轮只应装配一次 ROUTER");
        String slot = assembledQueries.get(0);
        assertFalse(slot.contains(firstQuestion));
        assertFalse(slot.contains(secondQuestion));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(react, org.mockito.Mockito.times(2)).judge(any(), promptCaptor.capture(), userCaptor.capture(),
                any(EffectiveLimits.class));
        List<String> systemPrompts = promptCaptor.getAllValues();
        List<String> users = userCaptor.getAllValues();
        assertEquals(List.of(firstQuestion, secondQuestion), users);
        assertTrue(systemPrompts.get(0).contains(firstQuestion));
        assertFalse(systemPrompts.get(0).contains(secondQuestion));
        assertTrue(systemPrompts.get(1).contains(secondQuestion));
        assertFalse(systemPrompts.get(1).contains(firstQuestion));
        assertFalse(systemPrompts.get(0).contains(slot));
        assertFalse(systemPrompts.get(1).contains(slot));
        String marker = "\u0000问句\u0000";
        assertEquals(systemPrompts.get(0).replace(firstQuestion, marker),
                systemPrompts.get(1).replace(secondQuestion, marker));
        verify(prompts, never()).build(any(), anyString());
    }

    /**
     * 反射进私有 {@code choose}。本测试不把选路结果升成公开 API。
     *
     * @param spec      入口方案
     * @param userText  用户原文
     * @param candidates 已装载候选
     * @param runtime   运行配置
     * @return {@code RoutedChoice}；类型保持私有，调用方再反射取意图
     */
    private Object choose(MaRunSpec spec, String userText, List<Candidate> candidates, AgentRuntimeConfig runtime)
            throws Exception {
        Method method = RouterJudge.class.getDeclaredMethod("choose", AgentDef.class, MaRunSpec.class,
                EffectiveLimits.class, AgentRuntimeConfig.class, String.class, List.class, long.class, long.class,
                long.class, RouteHint.class, TrialProgress.class);
        method.setAccessible(true);
        AgentDef parent = new AgentDef();
        parent.setId(1L);
        parent.setName("入口");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        EffectiveLimits limits = new EffectiveLimits(60_000L, 4, 10_000L, 0, 8, 30_000L, 8, 3, 4_000);
        try {
            return method.invoke(judge, parent, spec, limits, runtime, userText, candidates,
                    7L, 9L, deadline, null, null);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    /**
     * 取出私有选择结果里的意图列表。
     *
     * @param choice {@code choose} 的返回值
     * @return 命中意图
     */
    @SuppressWarnings("unchecked")
    private static List<RoutedIntent> intentsOf(Object choice) throws Exception {
        Method intents = choice.getClass().getDeclaredMethod("intents");
        intents.setAccessible(true);
        return (List<RoutedIntent>) intents.invoke(choice);
    }

    /**
     * 关闭多意图协作，避免关键字上限把测试口径带偏。判路由规则本身不改。
     *
     * @return 运行配置
     */
    private static AgentRuntimeConfig runtime() {
        AgentRuntimeConfig runtime = new AgentRuntimeConfig();
        runtime.setMultiIntentEnabled(false);
        return runtime;
    }

    /**
     * @param id   成员主键
     * @param name 展示名，覆盖判定按它认成员
     * @return 启用中的成员定义
     */
    private static AgentDef member(Long id, String name) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    /**
     * @param agentId 成员主键
     * @return 方案里的启用单兵候选
     */
    private static MaCandidate memberRow(Long agentId) {
        MaCandidate row = new MaCandidate();
        row.setAgentId(agentId);
        row.setEnabled(AgentStatus.ENABLED);
        row.setTargetType(MaCandidate.TARGET_AGENT);
        return row;
    }

    /**
     * 键和意图都用问句里不会出现的字，避免关键字先截走覆盖或判定。
     *
     * @param key    route_key
     * @param intent 关键字
     * @param child  子主键
     * @return 启用路由行
     */
    private static AgentRoute route(String key, String intent, Long child) {
        AgentRoute route = new AgentRoute();
        route.setRouteKey(key);
        route.setIntent(intent);
        route.setStatus(AgentStatus.ENABLED);
        route.setChildAgentId(child);
        return route;
    }
}
