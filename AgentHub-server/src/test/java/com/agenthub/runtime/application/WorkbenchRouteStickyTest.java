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
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作台会话线路沿用 / 改派 / 手动换线（刀3）。所属域：runtime。
 *
 * <p>只测判路由链路上的四种线路语义，不起 Spring、不连库、不出站模型：
 * <ol>
 *   <li>沿用提示注入：提示词里出现「本会话当前线路是 …」，返回同键则保持；</li>
 *   <li>Router 改派：返回别的键 → 粘性改写到新键，但不再补「已将线路改派」说明（入口句已说出本轮选择）；</li>
 *   <li>手动换线：跳过判路由直接命中，强制标记只影响这一轮（由结果对象表达）；</li>
 *   <li>多意图：粘性清空，下一轮重新判；</li>
 *   <li>强制目标已失效：回落正常判路由并留可见说明，不伪造命中。</li>
 * </ol>
 */
class WorkbenchRouteStickyTest {

    private static final long CHILD_AFTER_SALE = 20L;
    private static final long CHILD_ORDER = 21L;

    private AgentRouteRepository routes;
    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private SystemPromptAssembler prompts;
    private RuntimeLimitResolver limitResolver;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private ThreadPoolTaskExecutor pool;
    private RouterTrialExecutor executor;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        routes = mock(AgentRouteRepository.class);
        AgentDefRepository agents = mock(AgentDefRepository.class);
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        prompts = mock(SystemPromptAssembler.class);
        limitResolver = mock(RuntimeLimitResolver.class);
        memory = mock(TrialMemoryCoordinator.class);
        planRunner = mock(PlanTrialExecutor.class);
        ChildPlanApprovalRegistry childApprovals = new ChildPlanApprovalRegistry();
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
        when(loader.findRouterModel(anyLong())).thenReturn(Optional.empty());
        // 两个子专家：售后 / 订单，均启用且允许被编排
        when(agents.findById(CHILD_AFTER_SALE)).thenReturn(Optional.of(child(CHILD_AFTER_SALE, "售后助手")));
        when(agents.findById(CHILD_ORDER)).thenReturn(Optional.of(child(CHILD_ORDER, "订单助手")));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /** 沿用线路：提示词里点名当前线路与候选名，返回同键时粘性保持。 */
    @Test
    void stickyHintIsInjectedAndKeptWhenJudgeReturnsSameKey() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"after_sale\",\"sub_query\":\"退款进度\"}]");

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), spec(),
                "退款进度", "退款进度", 7L, 1L, null, RouteHint.sticky("after_sale"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(react).judge(any(), prompt.capture(), anyString(), any());
        assertTrue(prompt.getValue().contains("本会话当前线路是 after_sale"), prompt.getValue());
        assertTrue(prompt.getValue().contains("售后助手"), prompt.getValue());
        assertEquals("after_sale", routed.sticky().routeKey());
        assertEquals(CHILD_AFTER_SALE, routed.sticky().agentId());
        // 同一线路不算改派：时间线不额外加说明
        assertTrue(routed.notices().isEmpty(), String.valueOf(routed.notices()));
    }

    /**
     * Router 改派：返回别的键 → 粘性改写到新键，但不再补「已将线路改派」说明。
     *
     * <p>产品已删除这类说明：入口句已经按本轮选择说出交给谁，再补一句就是改口。
     * 这里锁住「只改粘性、不额外提示」，防止以后又把已删文案加回来。
     */
    @Test
    void judgeSwitchingKeyReassignsStickyWithoutNotice() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"order\",\"sub_query\":\"查订单\"}]");

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), spec(),
                "查订单", "查订单", 7L, 2L, null, RouteHint.sticky("after_sale"));

        assertEquals("order", routed.sticky().routeKey());
        assertEquals(CHILD_ORDER, routed.sticky().agentId());
        // 改派不再产生可见说明
        assertTrue(routed.notices().stream().noneMatch(item -> item.contains("改派")),
                String.valueOf(routed.notices()));
        // 也不把内部 route_key 抖给用户
        assertTrue(routed.notices().stream().noneMatch(item -> item.contains("after_sale")),
                String.valueOf(routed.notices()));
    }

    /** 手动换线：跳过判路由直接命中，说明写「手动换线」。 */
    @Test
    void forcedRouteSkipsJudgeAndHitsTarget() {
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), spec(),
                "完全无关的一句话", "完全无关的一句话", 7L, 3L, null,
                RouteHint.forced("after_sale", "order"));

        // 强制轮不花判路由调用
        verify(react, never()).judge(any(), any(), anyString(), any());
        assertEquals("order", routed.sticky().routeKey());
        assertEquals(CHILD_ORDER, routed.sticky().agentId());
        assertTrue(routed.notices().stream().anyMatch(item -> item.contains("手动换线")
                        && item.contains("订单助手")),
                String.valueOf(routed.notices()));
    }

    /** 多意图协作轮：粘性清空（下一轮重新判），且不加「改派」说明。 */
    @Test
    void multiIntentTurnClearsSticky() {
        when(react.judge(any(), any(), anyString(), any())).thenReturn(
                "[{\"route_key\":\"after_sale\",\"sub_query\":\"退款\"},"
                        + "{\"route_key\":\"order\",\"sub_query\":\"订单\"}]");

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), spec(),
                "退款和订单一起看", "退款和订单一起看", 7L, 4L, null, RouteHint.sticky("after_sale"));

        RouteSticky sticky = routed.sticky();
        assertNull(sticky.routeKey());
        assertNull(sticky.agentId());
        assertTrue(routed.notices().stream().noneMatch(item -> item.contains("改派")),
                String.valueOf(routed.notices()));
    }

    /** 强制目标已失效：不伪造命中，回落判路由并明确说明。 */
    @Test
    void missingForcedTargetFallsBackToJudgeWithNotice() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"after_sale\",\"sub_query\":\"退款\"}]");

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), spec(),
                "退款", "退款", 7L, 5L, null, RouteHint.forced("after_sale", "deleted_route"));

        assertEquals("after_sale", routed.sticky().routeKey());
        assertTrue(routed.notices().stream().anyMatch(item -> item.contains("已停用或不存在")
                        && item.contains("重新判定")),
                String.valueOf(routed.notices()));
    }

    /** 调试 / 试跑不传 hint：提示词里不能出现任何线路沿用说明。 */
    @Test
    void debugPathWithoutHintHasNoStickyPrompt() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"after_sale\",\"sub_query\":\"退款\"}]");

        executor.execute(parent(), spec(), "退款", "退款", 7L, 6L, null, null);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(react).judge(any(), prompt.capture(), anyString(), any());
        assertTrue(!prompt.getValue().contains("本会话当前线路"), prompt.getValue());
    }

    /** 虚拟根只读壳：与 TrialRunAppService 合成的形状一致（id 为空）。 */
    private AgentDef parent() {
        AgentDef agent = new AgentDef();
        agent.setName("演示方案");
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    private AgentDef child(Long id, String name) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode("child_" + id);
        agent.setRunMode(AgentRunMode.REACT.name());
        agent.setStatus(AgentStatus.ENABLED);
        agent.setAllowOrchestrated(1);
        return agent;
    }

    /** 两条候选的方案来源（ROUTER_PARALLEL，未配判路由模型时回落父 Agent 槽）。 */
    private MaRunSpec spec() {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        runtime.setMaTemplate("ROUTER_PARALLEL");
        return new MaRunSpec(5L, "ma_demo", "演示方案", "ROUTER_PARALLEL", runtime,
                List.of(candidate(CHILD_AFTER_SALE, "after_sale", 1), candidate(CHILD_ORDER, "order", 2)),
                null, 99L);
    }

    private MaCandidate candidate(Long agentId, String key, int sortNo) {
        MaCandidate item = new MaCandidate();
        item.setId(agentId + 1000);
        item.setMaId(5L);
        item.setAgentId(agentId);
        item.setRouteKey(key);
        item.setIntentDesc("意图" + key);
        item.setSortNo(sortNo);
        item.setEnabled(1);
        return item;
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }

    /** 关键字兜底不参与本测试：用户原文里刻意不含 route_key 子串。 */
    @SuppressWarnings("unused")
    private static AgentRoute route(String key) {
        AgentRoute route = new AgentRoute();
        route.setRouteKey(key);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }
}
