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
import com.agenthub.runtime.domain.service.LimitHitReasons;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 竞争选优（COMPETE）窄真执行测试。所属域：runtime。
 *
 * <p>只 mock 外部协作者（库、装配、ReAct、记忆、限制），跑真实分支：
 * 同题并行 / JUDGE_PICK 选优 / FIRST_SUCCESS / 评选失败降级 / 单子失败继续 / 候选不足中文 FAIL。
 * 不起 Spring，不连库，不出站模型。
 */
class CompeteRouterTrialExecutorTest {

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
        // 每个子拿到带自己 agentId 的 plan，测试据此给不同子返回不同终答
        when(loader.loadReact(any(), anyString(), any())).thenAnswer(invocation -> {
            AgentDef child = invocation.getArgument(0);
            TrialPlan plan = new TrialPlan();
            plan.setAgentId(child.getId());
            plan.setLimits(limits);
            plan.setNotices(new ArrayList<>());
            return plan;
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * 默认策略 JUDGE_PICK：评选模型选中 b，终答只能是 b 的正文，不拼接 a。
     */
    @Test
    void judgePickReturnsOnlyWinnerAnswer() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"JUDGE_PICK\","
                + "\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A（短）" : "答B（完整）");
        });
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn("{\"winner\":\"b\",\"reason\":\"覆盖更完整\"}");

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 31L);

        verify(react, times(1)).judge(any(), any(), any(), any());
        // 同题：两次子执行都送用户原问全文
        verify(react, times(2)).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertEquals("答B（完整）", routed.turn().getAssistantText());
        assertFalse(routed.turn().getAssistantText().contains("答A"), routed.turn().getAssistantText());
        // 时间线必须能看出谁是赢家
        assertTrue(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null && segment.routeLine().contains("★ 选中")),
                "缺少 winner 标注");
        assertTrue(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null && segment.routeLine().contains("winner=b")),
                "缺少选优结论行");
    }

    /**
     * FIRST_SUCCESS：不调用评选模型，直接取候选顺序第一个成功子。
     */
    @Test
    void firstSuccessSkipsJudge() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"FIRST_SUCCESS\"}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 32L);

        verify(react, never()).judge(any(), any(), any(), any());
        assertEquals("答A", routed.turn().getAssistantText());
    }

    /**
     * 评选模型失败：仍有成功子时降级 FIRST_SUCCESS，并在 notices 写明原因。
     */
    @Test
    void judgeFailureDegradesToFirstSuccessWithNotice() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"JUDGE_PICK\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("评选模型炸了"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 33L);

        assertEquals("答A", routed.turn().getAssistantText());
        assertTrue(routed.notices().stream().anyMatch(note -> note.contains("评选模型调用异常")),
                String.valueOf(routed.notices()));
        assertTrue(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null && segment.routeLine().contains("选优降级")),
                "缺少降级说明");
    }

    /**
     * 墙钟超时不降级：与其它模板同口径整次 FAIL。
     */
    @Test
    void judgeTimeoutFailsInsteadOfDegrading() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"JUDGE_PICK\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答"));
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenThrow(new TrialFailure("评选超时", LimitHitReasons.TIMEOUT));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 34L));
        assertEquals(LimitHitReasons.TIMEOUT, failure.getFinishReason());
    }

    /**
     * 单子失败不阻其它子：赢家仍可产出终答，失败子只留错误占位。
     */
    @Test
    void singleChildFailureStillYieldsFinalAnswer() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"JUDGE_PICK\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            if (plan.getAgentId() == 11L) {
                throw new RuntimeException("子B炸了");
            }
            return turn("答A");
        });
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn("{\"winner\":\"a\",\"reason\":\"唯一成功\"}");

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 35L);

        assertEquals("答A", routed.turn().getAssistantText());
        assertTrue(routed.segments().stream().anyMatch(segment -> segment.error() != null),
                "失败子缺少错误占位");
    }

    /**
     * 全部子失败：不拿占位段伪装成功，整次中文 FAIL。
     */
    @Test
    void allChildrenFailedFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"competeStrategy\":\"FIRST_SUCCESS\"}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("模型不可用"));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 36L));
        assertTrue(failure.getMessage().contains("全部子 Agent 均未完成"), failure.getMessage());
    }

    /**
     * 候选不足 2 个：保存侧与运行侧同一口径，必须中文说明。
     */
    @Test
    void fewerThanTwoCandidatesFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\"}");
        givenChildren(parent, "a");

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 37L));
        assertTrue(failure.getMessage().contains("至少需要 2 个可用候选"), failure.getMessage());
    }

    /**
     * 并行上限小于 2：与「至少 2 个选手」矛盾，明确指向配置项。
     */
    @Test
    void capBelowTwoFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"COMPETE\",\"maxRouteChildren\":1}");
        givenChildren(parent, "a", "b");

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 38L));
        assertTrue(failure.getMessage().contains("并行上限至少为 2"), failure.getMessage());
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
            when(agents.findById(childId))
                    .thenReturn(Optional.of(agent(childId, "子" + keys[i].toUpperCase(), AgentRunMode.REACT.name(),
                            AgentStatus.ENABLED)));
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
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    private AgentDef agent(Long id, String name, String runMode, int status) {
        return agent(id, name, runMode, status, null);
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
