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
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 执行器读 ma_def 方案的窄真执行测试（MA-P2 刀2）。所属域：runtime。
 *
 * <p>验证三条读源纪律，不改模板语义：
 * <ol>
 *   <li>候选只读 {@code ma_candidate}：{@code agent_route} 里有别的子也不该被执行；</li>
 *   <li>判路由模型优先取方案里的 {@code maJudgeModelInstanceId}，不回落 Agent 槽；</li>
 *   <li>方案就是自己的根：没有 Agent 根（parent 为 null）也能跑通固定模板，不依赖父 Agent 的模型槽。</li>
 * </ol>
 * 不起 Spring、不连库、不出站模型。
 */
class MaSpecRoutingTest {

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
        pool.setCorePoolSize(3);
        pool.setMaxPoolSize(3);
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
        // 旧口径的模型槽默认没有：读源若回落 Agent，判路由会直接失败，测试才能证明「确实读了方案」
        when(loader.findRouterModel(anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * 固定串行：候选来自方案，agent_route 里的「幽灵子」不得被执行。
     */
    @Test
    void fixedSequenceUsesCandidatesFromSpecInsteadOfAgentRoute() {
        AgentDef parent = parent(9L);
        givenRoute(parent, "from_route", 11L);
        givenAgent(20L, "方案子", 1);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("方案子答"));

        MaRunSpec spec = spec("FIXED_SEQUENCE", null, candidate(20L, "plan_child", 1));
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, spec, "用户原问", "用户原问",
                7L, 41L, null);

        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());
        verify(agents, never()).findById(11L);
        assertTrue(routed.turn().getAssistantText().contains("方案子答"));
    }

    /**
     * 判路由并行：模型实例取方案的 maJudgeModelInstanceId，不回落 Agent 的 ROUTER 槽。
     */
    @Test
    void routerParallelUsesJudgeModelFromSpec() {
        // 方案就是自己的根：执行器拿到的 parent 为 null，模型只能来自方案
        AgentDef parent = null;
        givenAgent(20L, "方案子", 1);
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"plan_child\",\"sub_query\":\"只问子问题\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));

        MaRunSpec spec = spec("ROUTER_PARALLEL", 99L, candidate(20L, "plan_child", 1));
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, spec, "帮我看看", "帮我看看",
                7L, 42L, null);

        ArgumentCaptor<MeterScope> scope = ArgumentCaptor.forClass(MeterScope.class);
        verify(react).judge(scope.capture(), any(), anyString(), any());
        assertEquals(99L, scope.getValue().modelInstanceId());
        // 方案链路没有 agent_def 行：计量归属保持空，不伪造成某个 Agent
        assertNull(scope.getValue().agentId());
        assertTrue(routed.turn().getAssistantText().contains("子答"));
    }

    /**
     * 方案（无 Agent 根）+ 固定并行：parent 为 null 也不能 NPE，模板与候选全来自方案。
     */
    @Test
    void schemeWithoutAgentRootRunsFixedParallel() {
        AgentDef parent = null;
        givenAgent(20L, "方案子", 1);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("方案子答"));

        MaRunSpec spec = spec("FIXED_PARALLEL", null, candidate(20L, "plan_child", 1));
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, spec, "用户原问", "用户原问",
                7L, 43L, null);

        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertTrue(routed.turn().getAssistantText().contains("方案子答"));
    }

    /**
     * 方案候选停用（enabled=0）不参与执行：执行器只认启用候选，与建方案时的过滤同口径。
     */
    @Test
    void disabledCandidateIsNotExecuted() {
        AgentDef parent = null;
        givenAgent(20L, "停用方案子", 1);
        MaRunSpec spec = spec("FIXED_SEQUENCE", null, candidate(20L, "plan_child", 0));

        // 候选为空：固定模板没有判路由兜底，必须整次中文 FAIL，而不是空跑出「无实质回答」
        com.agenthub.runtime.domain.service.TrialFailure failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.agenthub.runtime.domain.service.TrialFailure.class,
                        () -> executor.execute(parent, spec, "用户原问", "用户原问", 7L, 44L, null));
        assertTrue(failure.getMessage().contains("没有可用的子 Agent"), failure.getMessage());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /** 造绑根 ROUTER（带主键）。 */
    private AgentDef parent(Long id) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName("绑根路由器");
        agent.setCode("root_" + id);
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    /** 旧口径的路由行：用来证明方案优先时它不会被读到。 */
    private void givenRoute(AgentDef parent, String key, Long childId) {
        AgentRoute route = new AgentRoute();
        route.setId(childId + 100);
        route.setParentAgentId(parent.getId());
        route.setChildAgentId(childId);
        route.setRouteKey(key);
        route.setIntent("旧意图" + key);
        route.setStatus(AgentStatus.ENABLED);
        when(routes.listByParentId(parent.getId())).thenReturn(new ArrayList<>(List.of(route)));
    }

    /** 子 Agent：启用 + REACT + 允许被编排。 */
    private void givenAgent(Long id, String name, int status) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode("child_" + id);
        agent.setRunMode(AgentRunMode.REACT.name());
        agent.setStatus(status);
        agent.setAllowOrchestrated(1);
        when(agents.findById(id)).thenReturn(Optional.of(agent));
    }

    /** 造一条方案候选；enabled=0 表示停用。 */
    private MaCandidate candidate(Long agentId, String routeKey, int enabled) {
        MaCandidate item = new MaCandidate();
        item.setId(agentId + 1000);
        item.setMaId(5L);
        item.setAgentId(agentId);
        item.setRouteKey(routeKey);
        item.setIntentDesc("意图" + routeKey);
        item.setSortNo(1);
        item.setEnabled(enabled);
        return item;
    }

    /**
     * 构造方案执行来源。
     *
     * @param template  模板码
     * @param judgeId   判路由模型实例；null 表示未配
     * @param candidates 候选（调用方自己保证启用状态）
     */
    private MaRunSpec spec(String template, Long judgeId, MaCandidate candidates) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        runtime.setMaTemplate(template);
        List<MaCandidate> enabled = candidates.getEnabled() != null && candidates.getEnabled() == 1
                ? List.of(candidates) : List.of();
        return new MaRunSpec(5L, "ma_demo", "演示方案", template, runtime, enabled, null, judgeId);
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
