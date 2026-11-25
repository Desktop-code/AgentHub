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
 * 投票选优（VOTE）窄真执行测试。所属域：runtime。
 *
 * <p>只 mock 外部协作者（库、装配、ReAct、记忆、限制），跑真实分支：
 * 两路成功投票 / 仅一路成功跳过投票 / 评选失败降级 CONCAT / 无合法 winner 降级 /
 * 墙钟超时不降级 / 固定并行同样可投票。
 * 不起 Spring，不连库，不出站模型。
 */
class VoteRouterTrialExecutorTest {

    /** 判路由决策：两条意图各走一个子，sub_query 不同（与 COMPETE 同题竞争区分） */
    private static final String ROUTE_TWO = "[{\"route_key\":\"a\",\"sub_query\":\"子问A\"},"
            + "{\"route_key\":\"b\",\"sub_query\":\"子问B\"}]";

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
     * 两路成功 + 评选模型选中 b：终答只能是 b 的正文，不拼接 a；泳道与时间线都要能看出赢家。
     */
    @Test
    void votePicksWinnerAnswerWithoutRewriting() {
        AgentDef parent = parent(routerParallelVote());
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        // 第一次 judge＝判路由，第二次 judge＝投票评选
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn(ROUTE_TWO, "{\"winner\":\"b\",\"reason\":\"覆盖更完整\"}");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A（短）" : "答B（完整）");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 31L);

        // 判路由 1 次 + 投票 1 次；不因 VOTE 多调汇总模型
        verify(react, times(2)).judge(any(), any(), any(), any());
        assertEquals("答B（完整）", routed.turn().getAssistantText());
        assertFalse(routed.turn().getAssistantText().contains("答A"), routed.turn().getAssistantText());
        assertTrue(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null && segment.routeLine().contains("★ 选中")),
                "缺少 winner 标注");
        assertTrue(routed.segments().stream()
                        .anyMatch(segment -> segment.routeLine() != null && segment.routeLine().contains("winner=b")),
                "缺少投票结论行");
        assertTrue(routed.notices().stream().anyMatch(note -> note.contains("winner=b")),
                String.valueOf(routed.notices()));
    }

    /**
     * 仅一路成功：不调投票模型，直接出该子终答，并留 SYSTEM 说明。
     */
    @Test
    void singleSuccessSkipsVote() {
        AgentDef parent = parent(routerParallelVote());
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any())).thenReturn(ROUTE_TWO);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            if (plan.getAgentId() == 11L) {
                throw new RuntimeException("子B炸了");
            }
            return turn("答A");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 32L);

        // 只有判路由那一次，投票被跳过
        verify(react, times(1)).judge(any(), any(), any(), any());
        assertEquals("答A", routed.turn().getAssistantText());
        assertTrue(routed.notices().stream().anyMatch(note -> note.contains("跳过投票")),
                String.valueOf(routed.notices()));
    }

    /**
     * 评选调用异常（非墙钟超时）：降级 CONCAT，两段都在终答里，并写明降级原因。
     */
    @Test
    void voteFailureDegradesToConcat() {
        AgentDef parent = parent(routerParallelVote());
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn(ROUTE_TWO)
                .thenThrow(new RuntimeException("投票模型炸了"));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 33L);

        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("答A"), answer);
        assertTrue(answer.contains("答B"), answer);
        assertTrue(routed.notices().stream().anyMatch(note -> note.contains("投票") && note.contains("降级")),
                String.valueOf(routed.notices()));
    }

    /**
     * 评选模型没给出合法 winner：同样降级 CONCAT，不能猜一个赢家。
     */
    @Test
    void invalidWinnerDegradesToConcat() {
        AgentDef parent = parent(routerParallelVote());
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn(ROUTE_TWO, "{\"winner\":\"不存在的key\",\"reason\":\"瞎选\"}");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 34L);

        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("答A"), answer);
        assertTrue(answer.contains("答B"), answer);
        assertTrue(routed.notices().stream().anyMatch(note -> note.contains("未给出合法赢家")),
                String.valueOf(routed.notices()));
    }

    /**
     * 未配评选模型：不发投票出站，直接降级拼接。
     * <p>用固定并行造 2 个必跑子，避免「无判路由模型 → 只命中 1 个意图」把票池压成 1 路。
     */
    @Test
    void missingJudgeModelDegradesToConcat() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"VOTE\","
                + "\"multiIntentEnabled\":true,\"maxRouteChildren\":3}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.empty());
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 35L);

        verify(react, never()).judge(any(), any(), any(), any());
        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("答A"), answer);
        assertTrue(answer.contains("答B"), answer);
        assertTrue(routed.notices().stream().anyMatch(note -> note.contains("未配置评选模型")),
                String.valueOf(routed.notices()));
    }

    /**
     * 墙钟超时：沿用统一硬拦，不为 VOTE 单独降级。
     */
    @Test
    void voteTimeoutFailsInsteadOfDegrading() {
        AgentDef parent = parent(routerParallelVote());
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn(ROUTE_TWO)
                .thenThrow(new TrialFailure("投票超时", LimitHitReasons.TIMEOUT));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答"));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "用户原问", 7L, 36L));
        assertEquals(LimitHitReasons.TIMEOUT, failure.getFinishReason());
    }

    /**
     * 固定并行同样支持 VOTE：不判路由，只有一次投票调用。
     */
    @Test
    void fixedParallelAlsoVotes() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"VOTE\","
                + "\"multiIntentEnabled\":true,\"maxRouteChildren\":3}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn("{\"winner\":\"a\",\"reason\":\"更贴题\"}");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 37L);

        verify(react, times(1)).judge(any(), any(), any(), any());
        assertEquals("答A", routed.turn().getAssistantText());
    }

    /**
     * 模板不支持投票（固定串行）：配置归一为 CONCAT，一次评选模型都不调。
     */
    @Test
    void unsupportedTemplateNormalizesToConcat() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_SEQUENCE\",\"answerMode\":\"VOTE\","
                + "\"multiIntentEnabled\":true}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            TrialPlan plan = invocation.getArgument(0);
            return turn(plan.getAgentId() == 10L ? "答A" : "答B");
        });

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 38L);

        verify(react, never()).judge(any(), any(), any(), any());
        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("答A"), answer);
        assertTrue(answer.contains("答B"), answer);
    }

    /** ROUTER_PARALLEL + VOTE 的 runtime_config（默认隔离、允许两意图）。 */
    private String routerParallelVote() {
        return "{\"maTemplate\":\"ROUTER_PARALLEL\",\"answerMode\":\"VOTE\","
                + "\"multiIntentEnabled\":true,\"maxRouteChildren\":3}";
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
