package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 共享草稿区（SHARED_SCRATCH）在编排根上的挂载行为窄测。所属域：runtime。
 *
 * <p>只 mock 外部协作者（库、装配、ReAct、记忆、限制），跑真实分支，验证：
 * SHARED_SCRATCH 下并行子共享同一块板且各自带写入归属 / 未选策略时子上下文没有板 /
 * 根结束后板停止接受新写。真正「工具是否注册进 Toolkit」由 {@code MaScratchToolTest} 锁定。
 */
class RouterScratchBoardTest {

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
    /** 子执行期间看到的板：子标识 → 板（可空）；用 Optional 包一层，ConcurrentHashMap 不收 null 值 */
    private final Map<String, Optional<ScratchBoard>> boardsSeen = new ConcurrentHashMap<>();

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
        ScratchContext.clear();
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
        ScratchContext.clear();
    }

    /**
     * 并行两子共享同一块板：先写者占键、后写者冲突；根结束后板收尾。
     */
    @Test
    void parallelChildrenShareOneBoardAndCloseAfterRun() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_PARALLEL\",\"maContextPolicy\":\"SHARED_SCRATCH\","
                + "\"multiIntentEnabled\":true,\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},{\"route_key\":\"b\",\"sub_query\":\"子问B\"}]");
        // 两个子在同一块板上写同一个键：先到的成功、后到的被拒（拒绝双写，不覆盖）
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(this::writeAndCapture);

        executor.execute(parent, "用户原问", 7L, 61L);

        assertFalse(boardsSeen.isEmpty(), "并行子必须都能看到草稿板");
        Set<ScratchBoard> distinct = new LinkedHashSet<>();
        for (Optional<ScratchBoard> seen : boardsSeen.values()) {
            assertTrue(seen.isPresent(), "SHARED_SCRATCH 下每个子都必须拿到板");
            distinct.add(seen.get());
        }
        assertEquals(1, distinct.size(), "并行子共享同一块板，不允许每个子各建一块");
        ScratchBoard board = distinct.iterator().next();
        // 两个子都写了 draft/summary，但只有一个能写成功
        assertEquals(1, board.keyCount());
        assertEquals(1, board.conflictCount(), "第二个写同键的子必须撞冲突");
        // 根结束（成功）后板停止接受新写，下一轮直接丢弃
        assertTrue(board.closed());
        assertFalse(board.put("late", "v", "x").ok());
    }

    /**
     * 未选共享草稿（默认隔离）：子上下文没有板，因此不会注入 scratch 工具。
     */
    @Test
    void isolatedPolicyLeavesNoBoardInChild() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_PARALLEL\",\"multiIntentEnabled\":true,"
                + "\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},{\"route_key\":\"b\",\"sub_query\":\"子问B\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(this::writesIntoNullBoard);

        executor.execute(parent, "用户原问", 7L, 62L);

        assertEquals(2, boardsSeen.size(), "两个子都应被观察到");
        assertTrue(boardsSeen.values().stream().allMatch(Optional::isEmpty),
                "ISOLATED 下子上下文必须没有草稿板");
    }

    /**
     * 回归：串行模板的「前置摘要」不是共享草稿，不建板、不注入工具。
     */
    @Test
    void priorSummaryPolicyLeavesNoBoard() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_SEQUENCE\",\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(this::writesIntoNullBoard);

        executor.execute(parent, "用户原问", 7L, 63L);

        assertEquals(2, boardsSeen.size());
        assertTrue(boardsSeen.values().stream().allMatch(Optional::isEmpty),
                "PRIOR_SUMMARY_ONLY 下不应出现共享草稿板");
    }

    /**
     * 并行子模拟「写同一个键」并记录自己看到的板与写入归属。
     */
    private TrialTurn writeAndCapture(InvocationOnMock invocation) {
        ScratchContext.Scope scope = ScratchContext.current();
        assertNotNull(scope, "SHARED_SCRATCH 下子执行必须能看到草稿板");
        boardsSeen.put(scope.ownerRouteKey(), Optional.of(scope.board()));
        scope.board().put("draft/summary", "结论-" + scope.ownerRouteKey(), scope.ownerRouteKey());
        return turn("答-" + scope.ownerRouteKey());
    }

    /**
     * 未选共享草稿的子：记录自己看到的板（应为空）。
     */
    private TrialTurn writesIntoNullBoard(InvocationOnMock invocation) {
        ScratchContext.Scope scope = ScratchContext.current();
        // 用子问区分两次调用（无 route_key 归属时取 execute 的第 2 个入参）；
        // 显式取成 Object 再转字符串，避免 <T>getArgument 被推断成 char[] 触发重载
        Object second = invocation.getArgument(1);
        String key = second instanceof String text ? text : String.valueOf(second);
        boardsSeen.put(key, Optional.ofNullable(scope == null ? null : scope.board()));
        return turn("答");
    }

    private AgentDef parent(String runtimeJson) {
        return agent(1L, "父路由器", AgentRunMode.ROUTER.name(), AgentStatus.ENABLED, runtimeJson);
    }

    private void givenChildren(AgentDef parent, String... keys) {
        List<AgentRoute> rows = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            long childId = 10L + i;
            rows.add(route(keys[i], childId));
            when(agents.findById(childId)).thenReturn(Optional.of(
                    agent(childId, "子" + keys[i].toUpperCase(), AgentRunMode.REACT.name(),
                            AgentStatus.ENABLED, null)));
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
