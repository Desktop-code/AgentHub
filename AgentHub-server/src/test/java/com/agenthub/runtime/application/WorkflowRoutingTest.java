package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 判路由命中「工作流」候选时的派发窄测（刀5B）。所属域：runtime。
 *
 * <p>验证两条硬口径，全部 mock、不出站：
 * <ol>
 *   <li>判路由给出工作流线路的 route_key 时，执行器委托给 {@link WorkflowTargetRunner}
 *       （工作流域现网入口），不走 REACT 子；</li>
 *   <li>工作流候选被停用（enabled=0）时根本不进候选池，判路由无从命中。</li>
 * </ol>
 */
class WorkflowRoutingTest {

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
    private WorkflowTargetRunner workflowTargets;
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
        workflowTargets = mock(WorkflowTargetRunner.class);
        executor = new RouterTrialExecutor(routes, agents, loader, react, prompts, limitResolver, memory,
                planRunner, childApprovals, pool, workflowTargets);

        when(limitResolver.resolve(any())).thenReturn(limits);
        when(prompts.build(any(), any())).thenReturn("");
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
        when(loader.findRouterModel(anyLong())).thenReturn(Optional.empty());
        when(workflowTargets.run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new WorkflowTargetRunner.WorkflowTargetResult(turn("报销单已生成"),
                        List.of("运行工作流「报销审批」…")));
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /** 命中工作流线路：委托工作流域，REACT 子一次都不跑。 */
    @Test
    void judgeRoutingToWorkflowDelegatesInsteadOfReact() {
        // 方案就是自己的根：没有 Agent 根，parent 为 null；候选与目标全来自方案
        AgentDef parent = null;
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"wf_reimburse\",\"sub_query\":\"报销上个月差旅\"}]");

        MaRunSpec spec = spec(workflowCandidate(1), Optional.of(workflowTarget()));
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, spec, "我要报销差旅", "我要报销差旅",
                7L, 41L, null);

        verify(workflowTargets, times(1)).run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertTrue(routed.turn().getAssistantText().contains("报销单已生成"));
    }

    /** 工作流候选被停用：不进候选池，判路由不会命中，也不会委托出去。 */
    @Test
    void disabledWorkflowCandidateIsNotRoutable() {
        AgentDef parent = null;
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"wf_reimburse\",\"sub_query\":\"报销上个月差旅\"}]");

        MaRunSpec spec = spec(workflowCandidate(0), Optional.of(workflowTarget()));

        com.agenthub.runtime.domain.service.TrialFailure failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.agenthub.runtime.domain.service.TrialFailure.class,
                        () -> executor.execute(parent, spec, "我要报销差旅", "我要报销差旅", 7L, 42L, null));
        assertTrue(failure.getMessage().contains("没有可用的子 Agent"), failure.getMessage());
        verify(workflowTargets, never()).run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /** 工作流候选；enabled=0 表示停用。 */
    private static MaCandidate workflowCandidate(int enabled) {
        MaCandidate item = new MaCandidate();
        item.setId(9001L);
        item.setMaId(5L);
        item.setTargetType(MaCandidate.TARGET_WORKFLOW);
        item.setWorkflowDefId(88L);
        item.setRouteKey("wf_reimburse");
        item.setSortNo(1);
        item.setEnabled(enabled);
        return item;
    }

    /** 目标工作流快照：只绑默认发布指针，说明来自 workflow_def.description。 */
    private static WorkflowRunSpec workflowTarget() {
        return new WorkflowRunSpec(88L, "reimburse", "报销审批", "处理报销申请与审批流转", 900L);
    }

    /**
     * 构造方案执行来源（路由并行 + 指定判路由模型），并挂上工作流目标表。
     *
     * @param candidate 候选（调用方自己保证启用状态）
     * @param target    工作流目标；空表示装载失败
     */
    private static MaRunSpec spec(MaCandidate candidate, Optional<WorkflowRunSpec> target) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        runtime.setMaTemplate("ROUTER_PARALLEL");
        List<MaCandidate> enabled = candidate.getEnabled() != null && candidate.getEnabled() == 1
                ? List.of(candidate) : List.of();
        Map<Long, WorkflowRunSpec> targets = target
                .map(spec -> Map.of(spec.workflowId(), spec))
                .orElse(Map.of());
        return new MaRunSpec(5L, "ma_demo", "演示方案", "演示方案说明", "ROUTER_PARALLEL", runtime,
                enabled, null, 99L, true, Map.of(), targets);
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
