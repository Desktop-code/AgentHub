package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
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
 * allow_orchestrated 运行期门闩测试。所属域：runtime。
 *
 * <p>只验证候选可用性这一道新增过滤，不改模板执行语义：
 * 1) 被关开关的子不进执行，但在路由行留「已跳过：专家未允许被编排调用」；
 * 2) 三套模板共用同一装载逻辑：过滤后零可用 → 整次中文 FAIL；
 * 3) 部分可用 → 只跑允许的子，与「部分子失败仍汇总」的现网口径一致。
 *
 * <p>不起 Spring、不连库、不出站模型。
 */
class AllowOrchestratedRoutingTest {

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
     * 固定串行：唯一候选被关开关 → 零可用，整次 FAIL，中文点明「未允许被编排调用」。
     */
    @Test
    void fixedSequenceWithAllBlockedFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_SEQUENCE\"}");
        givenChild(parent, "a", 10L, 0);

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 31L));

        assertTrue(failure.getMessage().contains("没有可用的子 Agent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("未允许被编排调用"), failure.getMessage());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /**
     * 固定并行：一个允许 + 一个被关 → 只跑允许的子；被关的子进路由行且写明原因。
     */
    @Test
    void fixedParallelSkipsBlockedChildButStillRunsAllowedOne() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"CONCAT\"}");
        givenChild(parent, "allowed", 10L, 1);
        addChild(parent, "blocked", 11L, 0);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("允许的子答"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 32L);

        // 只执行允许的那一个；被关的不能假扇出
        verify(react, times(1)).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertEquals(2, routed.segments().size());
        assertTrue(routed.segments().stream()
                .anyMatch(segment -> segment.routeLine().contains("专家未允许被编排调用")));
        assertTrue(routed.turn().getAssistantText().contains("允许的子答"));
    }

    /**
     * 判路由并行：全部候选被关 → 直接 FAIL（连判路由 LLM 都不该调用）。
     */
    @Test
    void routerParallelWithAllBlockedFailsBeforeJudge() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_PARALLEL\",\"multiIntentEnabled\":true}");
        givenChild(parent, "a", 10L, 0);

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 33L));

        assertTrue(failure.getMessage().contains("未允许被编排调用"), failure.getMessage());
        verify(react, never()).judge(any(), any(), any(), any());
    }

    /** 造父 Agent（ROUTER + 指定 runtime_config）。 */
    private AgentDef parent(String runtimeJson) {
        return agent(1L, "父路由器", AgentRunMode.ROUTER.name(), AgentStatus.ENABLED, runtimeJson);
    }

    /** 挂第一个启用候选，并指定它的 allow_orchestrated。 */
    private void givenChild(AgentDef parent, String key, long childId, int allowOrchestrated) {
        AgentRoute route = route(key, childId);
        when(routes.listByParentId(parent.getId())).thenReturn(new ArrayList<>(List.of(route)));
        when(agents.findById(childId)).thenReturn(Optional.of(
                agent(childId, "子" + key, AgentRunMode.REACT.name(), AgentStatus.ENABLED, null, allowOrchestrated)));
    }

    /** 追加第二个候选（保持同一父的路由列表可变）。 */
    private void addChild(AgentDef parent, String key, long childId, int allowOrchestrated) {
        List<AgentRoute> rows = new ArrayList<>(routes.listByParentId(parent.getId()));
        rows.add(route(key, childId));
        when(routes.listByParentId(parent.getId())).thenReturn(rows);
        when(agents.findById(childId)).thenReturn(Optional.of(
                agent(childId, "子" + key, AgentRunMode.REACT.name(), AgentStatus.ENABLED, null, allowOrchestrated)));
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

    private AgentDef agent(Long id, String name, String runMode, int status, String runtimeJson) {
        return agent(id, name, runMode, status, runtimeJson, 1);
    }

    private AgentDef agent(Long id, String name, String runMode, int status, String runtimeJson,
                           int allowOrchestrated) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        agent.setRuntimeConfigJson(runtimeJson);
        agent.setAllowOrchestrated(allowOrchestrated);
        return agent;
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
