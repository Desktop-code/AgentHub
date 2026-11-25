package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.runtime.application.ChildPlanApprovalRegistry;
import com.agenthub.runtime.application.PlanTrialExecutor;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.application.TrialMemoryCoordinator;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

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
 * 「已发布工作流」作子候选时的执行委托测试（刀5B）。所属域：runtime。
 *
 * <p>验证三件不可退让的事，全部 mock、不出站、不连库：
 * <ol>
 *   <li>命中工作流候选必须委托给 {@link WorkflowTargetRunner}（同一会话），不能误走 REACT 子；</li>
 *   <li>入参取「本子子问」，缺失时才回落用户原文——与定稿里 {@code $.input.query} 对齐；</li>
 *   <li>未装配委托（只可能出现在窄测）时明确失败，不伪装成空回答。</li>
 * </ol>
 */
class WorkflowTargetDelegationTest {

    /** 外层墙钟：测试一律给足，避免时间因素干扰断言。 */
    private static final long DEADLINE = System.nanoTime() + 60_000_000_000L;

    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private ChildPlanApprovalRegistry approvals;
    private MaTargetRunner maTargets;
    private WorkflowTargetRunner workflowTargets;
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
        workflowTargets = mock(WorkflowTargetRunner.class);
        pool = mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        runner = new ChildRunner(loader, react, memory, planRunner, approvals, pool, maTargets, workflowTargets);
        when(workflowTargets.run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new WorkflowTargetRunner.WorkflowTargetResult(turn("流程已跑完：已生成报销单"),
                        List.of("运行工作流「报销审批」…")));
    }

    /** 命中工作流候选：委托工作流域入口，子 REACT 一次都不调用；终答沿用投影文案。 */
    @Test
    void workflowCandidateDelegatesToWorkflowRunner() {
        Candidate hit = new Candidate(route("wf_reimburse"),
                shell("报销审批", "reimburse"), null, workflowSpec());
        RoutedIntent intent = new RoutedIntent(hit, "帮我报销上个月的差旅", "流程类");

        ChildOutcome outcome = runner.runOneChild(parent(), intent, "帮我报销上个月的差旅", "帮我报销上个月的差旅",
                7L, 51L, DEADLINE, null, null, null, false, false, Thread.currentThread(), null);

        ArgumentCaptor<WorkflowRunSpec> specCaptor = ArgumentCaptor.forClass(WorkflowRunSpec.class);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        // 第 3 个参数是 userText（子问缺失时的兜底），这里断言子问原样传给工作流域
        verify(workflowTargets, times(1)).run(any(), specCaptor.capture(), queryCaptor.capture(),
                anyLong(), anyLong(), anyLong(), any());
        // 目标必须是候选里那份：按 workflow_def.id 定位，不能拿别的流程顶替
        assertEquals(88L, specCaptor.getValue().workflowId());
        // 入参＝本子子问（与 $.input.query 对齐）
        assertEquals("帮我报销上个月的差旅", queryCaptor.getValue());
        verify(react, never()).execute(any(), anyString(), anyLong(), anyLong(), any());
        assertTrue(outcome.hasAnswer());
        assertEquals("流程已跑完：已生成报销单", outcome.turn().getAssistantText());
        assertEquals(List.of("运行工作流「报销审批」…"), outcome.notices());
    }

    /** 子问为空时回落用户原文，避免把空白 query 丢给工作流。 */
    @Test
    void blankSubQueryFallsBackToUserText() {
        Candidate hit = new Candidate(route("wf_reimburse"),
                shell("报销审批", "reimburse"), null, workflowSpec());
        RoutedIntent intent = new RoutedIntent(hit, "  ", "流程类");

        runner.runOneChild(parent(), intent, "原始用户消息", "原始用户消息",
                7L, 52L, DEADLINE, null, null, null, false, false, Thread.currentThread(), null);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowTargets).run(any(), any(), queryCaptor.capture(), anyLong(), anyLong(), anyLong(), any());
        assertEquals("原始用户消息", queryCaptor.getValue());
    }

    /** 未装配工作流委托：明确失败，不静默吞掉这一条候选。 */
    @Test
    void missingRunnerFailsLoudly() {
        ChildRunner noRunner = new ChildRunner(loader, react, memory, planRunner, approvals, pool, maTargets);
        Candidate hit = new Candidate(route("wf_reimburse"),
                shell("报销审批", "reimburse"), null, workflowSpec());
        RoutedIntent intent = new RoutedIntent(hit, "帮我报销", "流程类");

        assertThrows(com.agenthub.runtime.domain.service.TrialFailure.class,
                () -> noRunner.runOneChild(parent(), intent, "帮我报销", "帮我报销",
                        7L, 53L, DEADLINE, null, null, null, false, false, Thread.currentThread(), null));
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

    /** 工作流候选的外壳 Agent：主键为空（绝不落库），只承载名称 / 编码 / 说明。 */
    private static AgentDef shell(String name, String code) {
        AgentDef agent = new AgentDef();
        agent.setName(name);
        agent.setCode(code);
        agent.setDescription("处理报销申请与审批流转");
        // 外壳填 ROUTER 只为与方案外壳同口径；执行分支在读取模式前就按 isWorkflowTarget 委托出去
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

    /** 目标工作流快照：只绑默认发布指针。 */
    private static WorkflowRunSpec workflowSpec() {
        return new WorkflowRunSpec(88L, "reimburse", "报销审批", "处理报销申请与审批流转", 900L);
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
