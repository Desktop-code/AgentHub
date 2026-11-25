package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.runtime.application.ChildPlanApprovalRegistry;
import com.agenthub.runtime.application.PlanTrialExecutor;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.application.TrialMemoryCoordinator;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 「另一套编排方案」作子候选时的执行委托测试（刀5）。所属域：runtime。
 *
 * <p>验证两件不可退让的事，全部 mock、不出站模型：
 * <ol>
 *   <li>命中 MA 候选必须委托给 {@link MaTargetRunner}（在同一会话上跑），不能误走 REACT 子；</li>
 *   <li>嵌套执行期间多子改为当前线程顺序推进，不再向 {@code routerTaskExecutor} 提交，
 *       避免「池线程等子任务」的自等待；退出后标记必须清零。</li>
 * </ol>
 */
class MaTargetDelegationTest {

    /** 外层墙钟：测试一律给足，避免时间因素干扰断言。 */
    private static final long DEADLINE = System.nanoTime() + 60_000_000_000L;

    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private ChildPlanApprovalRegistry approvals;
    private MaTargetRunner maTargets;
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool;
    private ChildRunner runner;

    @BeforeEach
    void setUp() {
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        memory = mock(TrialMemoryCoordinator.class);
        planRunner = mock(PlanTrialExecutor.class);
        approvals = new ChildPlanApprovalRegistry();
        maTargets = mock(MaTargetRunner.class);
        // 用 mock 而不是真池：本类正是要证明嵌套路径「一次都不提交」
        pool = mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        runner = new ChildRunner(loader, react, memory, planRunner, approvals, pool, maTargets);
        when(maTargets.run(any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new MaTargetRunner.MaTargetResult(turn("目标方案终答"), List.of("目标方案：售后编排")));
    }

    @AfterEach
    void tearDown() {
        // 防御：任何用例失败都不能把嵌套标记留给后续用例
        while (MaNestedContext.inside()) {
            MaNestedContext.exit();
        }
    }

    /**
     * 命中 MA 候选：委托目标方案，子 REACT 一次都不调用；终答与说明沿用目标方案返回。
     */
    @Test
    void maTargetCandidateDelegatesToTargetRunner() {
        MaRunSpec target = maSpec(77L, "售后编排");
        Candidate hit = new Candidate(route("after_sale"), shell("售后编排", "after_sale"), target);
        RoutedIntent intent = new RoutedIntent(hit, "我的订单一直没发货", "售后问题");

        ChildOutcome outcome = runner.runOneChild(parent(), intent, "我的订单一直没发货", "我的订单一直没发货",
                7L, 51L, DEADLINE, null, null, null, false, false, Thread.currentThread(), null);

        ArgumentCaptor<MaRunSpec> specCaptor = ArgumentCaptor.forClass(MaRunSpec.class);
        verify(maTargets, times(1)).run(any(), specCaptor.capture(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong(), any());
        // 目标方案必须就是候选里那份（键＝目标 ma_def.id），不能拿外层方案顶替
        assertEquals(77L, specCaptor.getValue().maId());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertTrue(outcome.hasAnswer());
        assertEquals("目标方案终答", outcome.turn().getAssistantText());
        assertEquals(List.of("目标方案：售后编排"), outcome.notices());
    }

    /**
     * 目标方案真汇总过：结论名与汇总推理必须随子结果带出去。
     *
     * <p>为什么必须带：落库时靠它写「方案结论：X」。丢了这一条，流式里画出来的汇总那条
     * 在收尾回放时就重建不出来——用户看到的是「综合台结论出过又消失」。
     */
    @Test
    void maTargetConclusionIsCarriedToChildOutcome() {
        MaRunSpec target = maSpec(77L, "企业综合台");
        Candidate hit = new Candidate(route("enterprise"), shell("企业综合台", "enterprise"), target);
        RoutedIntent intent = new RoutedIntent(hit, "整理知识要点并起草报告", "用户点名了该方案");
        when(maTargets.run(any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new MaTargetRunner.MaTargetResult(turn("汇总正文"), List.of(),
                        List.of(new NestedRoute("路由到 制度知识专家（policy），依据：查制度",
                                turn("制度要点"), null)),
                        "分流推理", "企业综合台", "汇总推理"));

        ChildOutcome outcome = runner.runOneChild(parent(), intent, "整理知识要点并起草报告",
                "整理知识要点并起草报告", 7L, 54L, DEADLINE, null, null, null, false, false,
                Thread.currentThread(), null);

        assertEquals("企业综合台", outcome.conclusionName());
        assertEquals("汇总推理", outcome.conclusionReasoning());
        assertEquals("分流推理", outcome.reasoning());
        assertEquals(1, outcome.children().size());
    }

    /**
     * 串行交接进方案：拼好的「子问 + 前置结论」要成为目标方案的用户问题，不能只把原问传进去。
     */    @Test
    void serialHandoffReachesMaTargetAsUserText() {
        MaRunSpec target = maSpec(77L, "售后编排");
        Candidate hit = new Candidate(route("after_sale"), shell("售后编排", "after_sale"), target);
        RoutedIntent intent = new RoutedIntent(hit, "写一版说明", "售后问题");
        String handed = "本轮问题：\n写一版说明\n\n【前置专家结论】\n- 第 1 步「知识专家」：制度正文";

        runner.runOneChild(parent(), intent, "用户原问", "带记忆的原问",
                7L, 53L, DEADLINE, null, null, null, false, true, Thread.currentThread(), handed);

        verify(maTargets).run(any(), any(), eq(handed), eq(handed), anyLong(), anyLong(), anyLong(), any());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /**
     * 嵌套标记生效时，多子改为当前线程顺序执行：不向池提交，结果顺序与并行一致。
     */
    @Test
    void nestedRunKeepsChildrenInline() {
        List<RoutedIntent> intents = new ArrayList<>();
        intents.add(maIntent(77L, "售后编排", "after_sale", "商品坏了要退"));
        intents.add(maIntent(78L, "物流编排", "logistics", "快递停在哪里"));

        List<ChildOutcome> outcomes;
        MaNestedContext.enter();
        try {
            // 目标方案自身在池线程上跑，其内部多子不能再抢同一个池
            assertTrue(MaNestedContext.inside());
            outcomes = runner.runChildren(parent(), intents, "原始输入", "原始输入", 7L, 52L, DEADLINE,
                    null, null, null, false);
        } finally {
            MaNestedContext.exit();
        }

        verify(maTargets, times(2)).run(any(), any(), anyString(), anyString(), anyLong(), anyLong(),
                anyLong(), any());
        verify(pool, never()).submit(any(Runnable.class));
        assertEquals(2, outcomes.size());
        assertEquals("after_sale", outcomes.get(0).key());
        assertEquals("logistics", outcomes.get(1).key());
        assertFalse(MaNestedContext.inside());
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
        runtime.setMaTemplate("ROUTER_PARALLEL");
        MaCandidate child = new MaCandidate();
        child.setId(maId + 1000);
        child.setMaId(maId);
        child.setAgentId(31L);
        child.setRouteKey("child_" + maId);
        child.setSortNo(1);
        child.setEnabled(1);
        return new MaRunSpec(maId, "ma_" + maId, name, "目标方案说明", "ROUTER_PARALLEL", runtime,
                List.of(child), null, 99L, false, Map.of(), Map.of());
    }

    private static RoutedIntent maIntent(Long maId, String name, String routeKey, String subQuery) {
        Candidate hit = new Candidate(route(routeKey), shell(name, routeKey), maSpec(maId, name));
        return new RoutedIntent(hit, subQuery, "业务判定");
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
