package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.runtime.application.ChildPlanApprovalRegistry;
import com.agenthub.runtime.application.PlanTrialExecutor;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.application.TrialMemoryCoordinator;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.SoleChainHolder;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 嵌套方案停等的三层硬约束。所属域：runtime 的测试。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>停等信号穿过子执行时<b>不被压成失败段</b>（否则用户看到假错误、且永远等不到续跑）；</li>
 *   <li>「本层是否单链」的标记在单子 / 多子下分别是 true / false（多子链不能开放停等）；</li>
 *   <li>停等边界带上真实嵌套坐标（depth / parentKey），方案卡 id 与直播同一算法。</li>
 * </ol>
 */
class MaNestedPauseSignalTest {

    private static final long DEADLINE = System.nanoTime() + 60_000_000_000L;

    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private MaTargetRunner maTargets;
    private ChildRunner runner;

    @BeforeEach
    void setUp() {
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        memory = mock(TrialMemoryCoordinator.class);
        planRunner = mock(PlanTrialExecutor.class);
        maTargets = mock(MaTargetRunner.class);
        runner = new ChildRunner(loader, react, memory, planRunner, new ChildPlanApprovalRegistry(),
                mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class), maTargets);
    }

    @AfterEach
    void tearDown() {
        // 防御：任何用例失败都不能把嵌套 / 单链标记留给后续用例
        while (MaNestedContext.inside()) {
            MaNestedContext.exit();
        }
        SoleChainHolder.clear();
    }

    /**
     * 目标方案抛出停等信号：子执行必须原样穿出去，不能被「失败继续」压成占位段。
     */
    @Test
    void pauseSignalIsNotSwallowedByFixedSequence() {
        when(maTargets.run(any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new MaPauseSignal(pausedState()));
        List<RoutedIntent> intents = List.of(maIntent(77L, "游戏研发团队", "game_team"));

        MaPauseSignal signal = assertThrows(MaPauseSignal.class, () -> runner.runFixedSequence(parent(),
                "做一个2048小游戏", "做一个2048小游戏", 7L, 51L, DEADLINE, null, null, null, intents,
                AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name()), null, List.of(), null, true));

        assertEquals(77L, signal.state().getMaDefId());
    }

    /**
     * 单子派发：下一层可以建停等边界（标记必须为 true），且退出后还原。
     */
    @Test
    void singleChildMarksSoleChain() {
        List<Boolean> seen = new ArrayList<>();
        when(maTargets.run(any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    seen.add(SoleChainHolder.allows());
                    return new MaTargetRunner.MaTargetResult(turn("目标方案终答"), List.of());
                });

        runner.runChildren(parent(), List.of(maIntent(77L, "游戏研发团队", "game_team")), "做一个2048小游戏",
                "做一个2048小游戏", 7L, 51L, DEADLINE, null, null, null, false, true);

        assertEquals(List.of(Boolean.TRUE), seen);
        assertFalse(SoleChainHolder.allows(), "退出后必须还原，不能把标记漏给同一请求的后续步骤");
    }

    /**
     * 多子派发（嵌套内并行）：不得开放停等，标记必须为 false。
     */
    @Test
    void multipleChildrenAreNotSoleChain() {
        List<Boolean> seen = new ArrayList<>();
        when(maTargets.run(any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    synchronized (seen) {
                        seen.add(SoleChainHolder.allows());
                    }
                    return new MaTargetRunner.MaTargetResult(turn("终答"), List.of());
                });
        List<RoutedIntent> intents = List.of(
                maIntent(77L, "游戏研发团队", "game_team"),
                maIntent(78L, "文书车间", "doc_shop"));

        MaNestedContext.enter("outer");
        try {
            runner.runChildren(parent(), intents, "原始输入", "原始输入", 7L, 52L, DEADLINE,
                    null, null, null, false);
        } finally {
            MaNestedContext.exit();
        }

        assertEquals(2, seen.size());
        assertTrue(seen.stream().noneMatch(Boolean::booleanValue));
    }

    /**
     * 停等边界必须带真实坐标：depth=1、parentKey=方案路由键；方案卡 id 与直播同一算法。
     */
    @Test
    void pauseScopeCarriesNestedCoordinates() {
        MaPauseScope scope = MaPauseScope.root(9L, "游戏研发团队", "ROUTER_THEN_SEQUENCE", 1, "game_team",
                "做一个2048小游戏", "做一个2048小游戏", List.of(), null, "分流推理", "[]");

        assertEquals(1, scope.depth());
        assertEquals("game_team", scope.parentKey());
        assertEquals("p-0-root-game_team", ChildRunner.participantIdOf(scope.depth() - 1, "", scope.parentKey()));
    }

    /**
     * 造停等快照：只为验证信号携带的状态。
     */
    private static MaPauseSnapshot pausedState() {
        MaPauseSnapshot state = new MaPauseSnapshot();
        state.setMaDefId(77L);
        state.setSchemeName("游戏研发团队");
        state.setTemplate("ROUTER_THEN_SEQUENCE");
        state.setDepth(1);
        state.setParentKey("game_team");
        return state;
    }

    /** 造编排根：只需 id / 名称供日志与子挂点使用。 */
    private static AgentDef parent() {
        AgentDef agent = new AgentDef();
        agent.setId(9L);
        agent.setName("默认接待");
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    /** MA 候选的外壳 Agent：主键为空（绝不落库），只承载名称 / 编码 / 说明。 */
    private static AgentDef shell(String name, String code) {
        AgentDef agent = new AgentDef();
        agent.setName(name);
        agent.setCode(code);
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    private static AgentRoute route(String routeKey) {
        AgentRoute route = new AgentRoute();
        route.setRouteKey(routeKey);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    /** 目标方案执行来源：一层，自身不再挂 MA 候选。 */
    private static MaRunSpec maSpec(Long maId, String name) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        runtime.setMaTemplate("ROUTER_THEN_SEQUENCE");
        MaCandidate child = new MaCandidate();
        child.setId(maId + 1000);
        child.setMaId(maId);
        child.setAgentId(31L);
        child.setRouteKey("child_" + maId);
        child.setSortNo(1);
        child.setEnabled(1);
        return new MaRunSpec(maId, "ma_" + maId, name, "目标方案说明", "ROUTER_THEN_SEQUENCE", runtime,
                List.of(child), null, 99L, false, Map.of(), Map.of());
    }

    private static RoutedIntent maIntent(Long maId, String name, String routeKey) {
        Candidate hit = new Candidate(route(routeKey), shell(name, routeKey), maSpec(maId, name));
        return new RoutedIntent(hit, "做一个2048小游戏", "用户点名了该方案");
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
