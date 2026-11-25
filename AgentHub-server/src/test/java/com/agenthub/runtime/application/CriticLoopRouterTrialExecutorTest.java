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
import org.mockito.ArgumentCaptor;
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
 * 评审环（CRITIC_LOOP）单轮真执行窄测。所属域：runtime。
 *
 * <p>只 mock 外部协作者（库、装配、ReAct、记忆、限制），跑真实分支：
 * 跳过判路由 / 执行者初稿 / 评审判定 / 有限回修 / 解析失败按 ACCEPT / 角色校验。
 * 不起 Spring，不连库，不出站模型。
 */
class CriticLoopRouterTrialExecutorTest {

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
        pool.setQueueCapacity(4);
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
     * ACCEPT：执行者 1 次 + 评审 1 次 → 终答＝初稿；不判路由、不拼接。
     */
    @Test
    void acceptReturnsDraftAfterSingleReview() {
        AgentDef parent = criticParent(1, 99L);
        givenExecutor(parent, 10L);
        givenCritic(99L, true);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("这是初稿正文"),
                turn("{\"decision\":\"ACCEPT\",\"feedback\":\"\"}"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 31L);

        // 评审环没有判路由 LLM：一次都不能调用
        verify(react, never()).judge(any(), any(), any(), any());
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), texts.capture(), anyLong(), anyLong(), any());
        // 初稿吃用户原问全文；评审输入含原问 + 当前稿 + 判定契约
        assertEquals("用户原问", texts.getAllValues().get(0));
        assertTrue(texts.getAllValues().get(1).contains("用户原问"), texts.getAllValues().get(1));
        assertTrue(texts.getAllValues().get(1).contains("这是初稿正文"), texts.getAllValues().get(1));
        assertTrue(texts.getAllValues().get(1).contains("decision"), texts.getAllValues().get(1));

        // 终答＝初稿，无「拼接 / 失败」占位
        assertEquals("这是初稿正文", routed.turn().getAssistantText());
        assertTrue(routed.segments().stream()
                .anyMatch(segment -> segment.routeLine().contains("第 1 轮结论")));
    }

    /**
     * REVISE + maxRounds=1：初稿 → 评审 → 回修 1 次 → 终答＝回修稿，不再二次评审。
     */
    @Test
    void reviseWithSingleRoundRevisesOnceWithoutSecondReview() {
        AgentDef parent = criticParent(1, 99L);
        givenExecutor(parent, 10L);
        givenCritic(99L, true);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("初稿"),
                turn("{\"decision\":\"REVISE\",\"feedback\":\"补充价格与时效\"}"),
                turn("回修稿"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 32L);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(react, times(3)).execute(any(), texts.capture(), anyLong(), anyLong(), any());
        // 回修输入带着原问、上一稿与评审意见；不会把评审 JSON 直接当成答复
        String reviseInput = texts.getAllValues().get(2);
        assertTrue(reviseInput.contains("用户原问"), reviseInput);
        assertTrue(reviseInput.contains("初稿"), reviseInput);
        assertTrue(reviseInput.contains("补充价格与时效"), reviseInput);

        assertEquals("回修稿", routed.turn().getAssistantText());
    }

    /**
     * 评审输出无法解析：按 ACCEPT 处理，终答＝当前稿，禁止因解析失败回修。
     */
    @Test
    void unparsableReviewIsAcceptedWithoutRevise() {
        AgentDef parent = criticParent(1, 99L);
        givenExecutor(parent, 10L);
        givenCritic(99L, true);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(turn("初稿"), turn("我觉得还可以，没什么要改的"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 33L);

        // 只有初稿 + 评审两次调用；没有回修
        verify(react, times(2)).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertEquals("初稿", routed.turn().getAssistantText());
        assertTrue(routed.segments().stream()
                .anyMatch(segment -> segment.routeLine().contains("无法解析")));
    }

    /**
     * 评审失败降级：终答＝当前执行稿 + 中文可见说明，整次不 FAIL。
     */
    @Test
    void criticFailureDegradesToDraft() {
        AgentDef parent = criticParent(1, 99L);
        givenExecutor(parent, 10L);
        givenCritic(99L, true);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(turn("初稿"))
                .thenThrow(new RuntimeException("评审模型炸了"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 34L);

        assertTrue(routed.turn().getAssistantText().startsWith("初稿"), routed.turn().getAssistantText());
        assertTrue(routed.turn().getAssistantText().contains("评审未完成，已返回执行稿"),
                routed.turn().getAssistantText());
        // 异常原文不能透给用户
        assertFalse(routed.turn().getAssistantText().contains("评审模型炸了"));
    }

    /**
     * 轮次硬顶：配置 9 轮被夹到 3 轮；每轮 REVISE 时共 1 初稿 + 3 评审 + 3 回修＝7 次调用。
     */
    @Test
    void roundsAreClampedToThree() {
        AgentDef parent = criticParent(9, 99L);
        givenExecutor(parent, 10L);
        givenCritic(99L, true);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(
                turn("初稿"),
                turn("{\"decision\":\"REVISE\",\"feedback\":\"再来\"}"),
                turn("回修1"),
                turn("{\"decision\":\"REVISE\",\"feedback\":\"再来\"}"),
                turn("回修2"),
                turn("{\"decision\":\"REVISE\",\"feedback\":\"再来\"}"),
                turn("回修3"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 35L);

        verify(react, times(7)).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertTrue(routed.turn().getAssistantText().startsWith("回修3"), routed.turn().getAssistantText());
        assertTrue(routed.turn().getAssistantText().contains("已达评审轮次上限（3 轮）"),
                routed.turn().getAssistantText());
    }

    /**
     * 执行者候选多于 1 个：校验 error，整次 FAIL，不能悄悄挑一个跑。
     */
    @Test
    void moreThanOneExecutorFails() {
        AgentDef parent = criticParent(1, 99L);
        List<AgentRoute> rows = new ArrayList<>();
        rows.add(route("a", 10L, 10));
        rows.add(route("b", 11L, 20));
        when(routes.listByParentId(1L)).thenReturn(rows);
        when(agents.findById(10L)).thenReturn(Optional.of(agent(10L, "执行者一", AgentRunMode.REACT.name(), 1, true)));
        when(agents.findById(11L)).thenReturn(Optional.of(agent(11L, "执行者二", AgentRunMode.REACT.name(), 1, true)));
        givenCritic(99L, true);

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 36L));
        assertTrue(failure.getMessage().contains("只允许 1 个执行者候选"), failure.getMessage());
    }

    /**
     * 零执行者候选：中文 FAIL，并说明候选需满足的开关。
     */
    @Test
    void zeroExecutorFailsInChinese() {
        AgentDef parent = criticParent(1, 99L);
        when(routes.listByParentId(1L)).thenReturn(List.of());
        givenCritic(99L, true);

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 37L));
        assertTrue(failure.getMessage().contains("恰好 1 个启用中的执行者候选"), failure.getMessage());
    }

    /**
     * 评审者未开启「允许被编排调用」：拒跑（与保存侧同一份中文原因）。
     */
    @Test
    void criticWithoutAllowOrchestratedIsRejected() {
        AgentDef parent = criticParent(1, 99L);
        givenExecutor(parent, 10L);
        givenCritic(99L, false);

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 38L));
        assertTrue(failure.getMessage().contains("未允许被编排调用"), failure.getMessage());
    }

    /**
     * 评审者 = 唯一候选：报「不能与执行者相同」，而不是含糊的「0 个执行者」。
     */
    @Test
    void criticEqualsOnlyCandidateIsRejected() {
        AgentDef parent = criticParent(1, 10L);
        givenExecutor(parent, 10L);
        when(agents.findById(10L)).thenReturn(Optional.of(
                agent(10L, "执行者", AgentRunMode.REACT.name(), 1, true)));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 39L));
        assertTrue(failure.getMessage().contains("评审专家不能与执行者相同"), failure.getMessage());
    }

    /** 造父 Agent（ROUTER + 评审环配置）。 */
    private AgentDef criticParent(int maxRounds, Long criticId) {
        String json = "{\"maTemplate\":\"CRITIC_LOOP\",\"maCriticAgentId\":" + criticId
                + ",\"maCriticMaxRounds\":" + maxRounds + "}";
        return agent(1L, "评审环根", AgentRunMode.ROUTER.name(), 1, true, json);
    }

    /** 挂 1 个启用的 REACT 执行者候选。 */
    private void givenExecutor(AgentDef parent, long childId) {
        when(routes.listByParentId(parent.getId())).thenReturn(List.of(route("exec", childId, 10)));
        when(agents.findById(childId)).thenReturn(Optional.of(
                agent(childId, "执行者", AgentRunMode.REACT.name(), 1, true)));
    }

    /** 评审者不在候选表也能被选中：只要 findById 查得到且合法。 */
    private void givenCritic(long criticId, boolean allowOrchestrated) {
        when(agents.findById(criticId)).thenReturn(Optional.of(
                agent(criticId, "评审专家", AgentRunMode.REACT.name(), 1, allowOrchestrated)));
    }

    private AgentRoute route(String key, Long childId, int sortOrder) {
        AgentRoute route = new AgentRoute();
        route.setId(childId + 100);
        route.setParentAgentId(1L);
        route.setChildAgentId(childId);
        route.setRouteKey(key);
        route.setIntent("意图" + key);
        route.setSortOrder(sortOrder);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    private AgentDef agent(Long id, String name, String runMode, int status, boolean allowOrchestrated) {
        return agent(id, name, runMode, status, allowOrchestrated, null);
    }

    private AgentDef agent(Long id, String name, String runMode, int status, boolean allowOrchestrated,
                           String runtimeJson) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        agent.setAllowOrchestrated(allowOrchestrated ? 1 : 0);
        agent.setRuntimeConfigJson(runtimeJson);
        return agent;
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
