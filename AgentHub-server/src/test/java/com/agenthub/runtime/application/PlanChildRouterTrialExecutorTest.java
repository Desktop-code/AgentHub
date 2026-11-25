package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排子 P&E 派发 + 批准冒泡的窄测试。所属域：runtime。
 *
 * <p>只验证「子执行器选择」与「单批准槽冒泡」两条新增分支，不重测模板语义：
 * 1) 允许 P&E 时，P&E 子走 {@link PlanTrialExecutor}（复用规划/执行），REACT 子仍走 ReAct；
 * 2) require_plan_confirm=false 时不暂停、不登记待批，直接跑完进汇总；
 * 3) require_plan_confirm=true 时登记待批并由根侧批准后继续，终答进汇总；
 * 4) 拒绝时该子失败占位，同轮其它 REACT 子照常成功汇总。
 *
 * <p>不起 Spring、不连库、不出站模型；批准用仓库真实实现模拟另一个 HTTP 请求。
 */
class PlanChildRouterTrialExecutorTest {

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
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * confirm=false：P&E 子直接走子内规划 → 执行，不登记待批，与 REACT 子一起进汇总。
     */
    @Test
    void planChildWithoutConfirmRunsThroughPlanExecutor() {
        AgentDef parent = parent("FIXED_PARALLEL", true);
        givenPlanAndReactChildren(null);
        when(planRunner.plan(any(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(planned(PlanState.EXECUTING, "plan-auto"));
        when(planRunner.execute(any(), any(PendingPlan.class), any(), eq(false)))
                .thenReturn(executed("计划结论"));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("ReAct 结论"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "帮我分析", 7L, 41L);

        // 子 P&E 走规划 + 复用执行器，且 openRoot=false（PLAN/步骤挂在子 AGENT 下，不出现假并行）
        verify(planRunner, times(1)).plan(any(), anyString(), anyString(), anyLong(), anyLong(), any());
        verify(planRunner, times(1)).execute(any(), any(PendingPlan.class), any(), eq(false));
        // REACT 子仍走现网 ReAct 路径，不被 P&E 分支劫持
        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());

        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("计划结论"), answer);
        assertTrue(answer.contains("ReAct 结论"), answer);
        // confirm=false 不占槽、不进待批：登记表必须为空
        assertTrue(childApprovals.peek(41L).isEmpty());
    }

    /**
     * confirm=true：规划后根暂停，登记待批；批准后子继续执行并把终答并入汇总。
     */
    @Test
    void confirmingPlanChildPausesUntilApproved() throws Exception {
        AgentDef parent = parent("FIXED_PARALLEL", true);
        givenPlanOnlyChild("{\"requirePlanConfirm\":true}");
        when(planRunner.plan(any(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(planned(PlanState.PENDING, "plan-wait"));
        when(planRunner.execute(any(), any(PendingPlan.class), any(), eq(false)))
                .thenReturn(executed("批准后的结论"));

        FutureTask<RouterTrialExecutor.RoutedTurn> run = startAsync(parent, "帮我分析", 42L);
        ChildPlanApprovalRegistry.PendingChildPlan waiting = awaitPending(42L);
        // 冒泡载荷必须带 route_key + 中文子名，前端据此定位批的是哪个子
        assertEquals("plan", waiting.routeKey());
        assertEquals("规划专家", waiting.childAgentName());
        // 批准前子不应进入执行：executor 一次都不能被调用
        verify(planRunner, never()).execute(any(), any(PendingPlan.class), any(), eq(false));
        assertTrue(childApprovals.approve(42L, "plan"));

        String answer = awaitResult(run).turn().getAssistantText();
        assertTrue(answer.contains("批准后的结论"), answer);
        assertTrue(childApprovals.peek(42L).isEmpty());
    }

    /**
     * 拒绝：该子失败占位（中文），同轮 REACT 子仍成功并进汇总；根不整次 FAIL。
     */
    @Test
    void rejectedPlanChildBecomesPlaceholderWhileOthersContinue() throws Exception {
        AgentDef parent = parent("FIXED_PARALLEL", true);
        givenPlanAndReactChildren("{\"requirePlanConfirm\":true}");
        when(planRunner.plan(any(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(planned(PlanState.PENDING, "plan-reject"));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("ReAct 结论"));

        FutureTask<RouterTrialExecutor.RoutedTurn> run = startAsync(parent, "帮我分析", 43L);
        awaitPending(43L);
        assertTrue(childApprovals.reject(43L, "plan"));

        // 被拒子不执行：executor 一次都不能被调用
        verify(planRunner, never()).execute(any(), any(PendingPlan.class), any(), eq(false));
        String answer = awaitResult(run).turn().getAssistantText();
        assertTrue(answer.contains("（执行失败）"), answer);
        assertTrue(answer.contains("计划未获批准"), answer);
        assertTrue(answer.contains("ReAct 结论"), answer);
    }

    /**
     * 子 P&E 但编排未允许：不进执行，等同无可用候选 → 整次中文 FAIL。
     */
    @Test
    void planChildRejectedWhenParentDoesNotAllowIt() {
        AgentDef parent = parent("FIXED_PARALLEL", false);
        givenPlanOnlyChild(null);
        when(routes.listByParentId(1L)).thenReturn(List.of(route("plan", 2L)));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "帮我分析", 7L, 44L));
        assertTrue(failure.getMessage().contains("没有可用的子 Agent"), failure.getMessage());
        verify(planRunner, never()).plan(any(), anyString(), anyString(), anyLong(), anyLong(), any());
    }

    /** 起线程跑根执行，主线程好去模拟「另一个 HTTP 请求」批准。 */
    private FutureTask<RouterTrialExecutor.RoutedTurn> startAsync(AgentDef parent, String userText, long sessionId) {
        FutureTask<RouterTrialExecutor.RoutedTurn> task =
                new FutureTask<>(() -> executor.execute(parent, userText, 7L, sessionId));
        Thread worker = new Thread(task, "plan-child-test-" + sessionId);
        worker.setDaemon(true);
        worker.start();
        return task;
    }

    /** 等根执行收口；超时说明批准没把等待中的子唤醒。 */
    private RouterTrialExecutor.RoutedTurn awaitResult(FutureTask<RouterTrialExecutor.RoutedTurn> task)
            throws Exception {
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new AssertionError("根执行未在 3s 内结束，批准可能未唤醒子线程");
        }
    }

    /** 轮询登记表，直到出现待批子（上限 2s）。 */
    private ChildPlanApprovalRegistry.PendingChildPlan awaitPending(long sessionId) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            Optional<ChildPlanApprovalRegistry.PendingChildPlan> found = childApprovals.peek(sessionId);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("未在 2s 内出现子计划待批");
    }

    /** 父：ROUTER + 指定模板；allowPlan 决定是否把 P&E 放进允许集合。 */
    private AgentDef parent(String template, boolean allowPlan) {
        String childModes = allowPlan ? "[\"REACT\",\"PLAN_AND_EXECUTE\"]" : "[\"REACT\"]";
        AgentDef parent = new AgentDef();
        parent.setId(1L);
        parent.setName("父编排");
        parent.setCode("parent");
        parent.setRunMode(AgentRunMode.ROUTER.name());
        parent.setStatus(AgentStatus.ENABLED);
        parent.setRuntimeConfigJson("{\"maTemplate\":\"" + template + "\",\"answerMode\":\"CONCAT\","
                + "\"maChildRunModesAllowed\":" + childModes + "}");
        return parent;
    }

    /** 一个 P&E 子（route_key=plan）+ 一个 REACT 子（route_key=react）；planRuntime 可覆盖子配置。 */
    private void givenPlanAndReactChildren(String planRuntime) {
        when(routes.listByParentId(1L)).thenReturn(List.of(route("plan", 2L), route("react", 3L)));
        when(agents.findById(2L)).thenReturn(Optional.of(
                child(2L, "规划专家", AgentRunMode.PLAN_AND_EXECUTE.name(), planRuntime)));
        when(agents.findById(3L)).thenReturn(Optional.of(
                child(3L, "ReAct 专家", AgentRunMode.REACT.name(), null)));
    }

    /** 只有一个 P&E 子；runtimeJson 非空时覆盖子自身配置（如 requirePlanConfirm）。 */
    private void givenPlanOnlyChild(String runtimeJson) {
        when(routes.listByParentId(1L)).thenReturn(List.of(route("plan", 2L)));
        when(agents.findById(2L)).thenReturn(Optional.of(
                child(2L, "规划专家", AgentRunMode.PLAN_AND_EXECUTE.name(), runtimeJson)));
    }

    private AgentRoute route(String key, Long childId) {
        AgentRoute route = new AgentRoute();
        route.setId(childId + 100);
        route.setParentAgentId(1L);
        route.setChildAgentId(childId);
        route.setRouteKey(key);
        route.setIntent("意图" + key);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    private AgentDef child(Long id, String name, String runMode, String runtimeJson) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setRunMode(runMode);
        agent.setStatus(AgentStatus.ENABLED);
        agent.setRuntimeConfigJson(runtimeJson);
        return agent;
    }

    private PlanTrialExecutor.PlannedTurn planned(PlanState state, String planId) {
        return new PlanTrialExecutor.PlannedTurn(planId, List.of(new PlanStep(1, "查资料", "")), state,
                null, "子问题");
    }

    private PlanTrialExecutor.ExecutedTurn executed(String text) {
        return new PlanTrialExecutor.ExecutedTurn(turn(text), "plan-done", List.of(text));
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
