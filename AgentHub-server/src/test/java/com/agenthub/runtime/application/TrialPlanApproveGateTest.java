package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批准 / 取消计划的门禁：没有待批准计划时必须拒绝，且不能误触 ReAct 或 Router 执行器。
 * 这里只测编排入口，不触网、不连库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrialPlanApproveGateTest {

    @Mock
    private com.agenthub.runtime.domain.repository.RunSessionRepository sessions;
    @Mock
    private com.agenthub.runtime.domain.repository.RunEventRepository events;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private TrialAccess access;
    @Mock
    private TrialBindingLoader loader;
    @Mock
    private com.agenthub.runtime.infra.agentscope.ReActTrialExecutor react;
    @Mock
    private RouterTrialExecutor router;
    @Mock
    private PlanTrialExecutor planRunner;
    @Mock
    private TrialEventWriter writer;
    @Mock
    private TrialFlight flight;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private TrialObsHooks obs;
    @Mock
    private TrialMemoryCoordinator memory;
    @Mock
    private com.agenthub.runtime.infra.agentscope.VisionOcrExecutor vision;
    @Mock
    private SessionTitleGeneratorAppService titleGenerator;
    @Mock
    private MaTrialSpecLoader maSpecs;
    @Mock
    private TrialMessageIdempotency idempotency;
    @Mock
    private com.agenthub.agent.application.ReceptionSchemeResolver reception;

    private PlanPendingRegistry pendingPlans;
    private TrialRunAppService service;

    @BeforeEach
    void setUp() {
        pendingPlans = new PlanPendingRegistry();
        service = new TrialRunAppService(sessions, events, agents, access, loader, react, router, planRunner,
                pendingPlans, new ChildPlanApprovalRegistry(), writer, flight, operatorContext, operLogWriter, obs,
                memory, new TrialLiveBuffer(), vision, titleGenerator, maSpecs, idempotency, reception,
                new WorkbenchRouteForceRegistry(), org.mockito.Mockito.mock(StaleRunCloser.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserImageStorage.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserFileStorage.class),
                org.mockito.Mockito.mock(TrialAttachmentReader.class),
                org.mockito.Mockito.mock(EntryPreprocess.class),
                org.mockito.Mockito.mock(WorkflowReplyResumer.class),
                org.mockito.Mockito.mock(MaReplyResumer.class));
    }

    @Test
    void approveWithoutPendingPlanIsRejected() {
        com.agenthub.runtime.domain.model.RunSession session = session();
        when(access.require(7L)).thenReturn(session);
        when(operatorContext.requireUserId()).thenReturn(100L);

        BizException ex = assertThrows(BizException.class, () -> service.approvePlan(7L));
        assertTrue(ex.getMessage().contains("没有待批准的计划"));
        // 没有计划就绝不能开跑，否则会凭空把上一轮的计划执行两遍
        verify(flight, never()).tryStart(anyLong());
    }

    @Test
    void rejectWithoutPendingPlanIsRejected() {
        when(access.require(7L)).thenReturn(session());
        when(operatorContext.requireUserId()).thenReturn(100L);

        assertThrows(BizException.class, () -> service.rejectPlan(7L));
        verify(flight, never()).tryStart(anyLong());
    }

    @Test
    void pendingPlanIsNotVisibleToOtherUsers() {
        pendingPlans.put(new PendingPlan("plan-1", 7L, 9L, 999L, "原文", "模型输入",
                List.of(new PlanStep(1, "一步", "")), java.time.LocalDateTime.now()));
        when(access.require(7L)).thenReturn(session());
        when(operatorContext.requireUserId()).thenReturn(100L);

        // 登记项属于 999 号用户，当前用户 100 拿不到，也不能借批准接口越权执行
        assertTrue(service.pendingPlan(7L).isEmpty());
        assertThrows(BizException.class, () -> service.approvePlan(7L));
    }

    @Test
    void pendingPlanIsExposedAsPendingView() {
        pendingPlans.put(new PendingPlan("plan-1", 7L, 9L, 100L, "原文", "模型输入",
                List.of(new PlanStep(1, "一步", "")), java.time.LocalDateTime.now()));
        when(access.require(7L)).thenReturn(session());
        when(operatorContext.requireUserId()).thenReturn(100L);

        var view = service.pendingPlan(7L).orElseThrow();
        // 查询接口只报告「待批准」，不暴露执行态：执行态由事件时间线承载
        assertTrue(view.state() == PlanState.PENDING);
        assertFalse(view.steps().isEmpty());
    }

    @Test
    void approveRejectedWhenAgentMissing() {
        pendingPlans.put(new PendingPlan("plan-1", 7L, 9L, 100L, "原文", "模型输入",
                List.of(new PlanStep(1, "一步", "")), java.time.LocalDateTime.now()));
        when(access.require(7L)).thenReturn(session());
        when(operatorContext.requireUserId()).thenReturn(100L);
        when(flight.tryStart(7L)).thenReturn(true);
        when(agents.findById(9L)).thenReturn(Optional.empty());
        // fail() 走 6 参重载：sessionId / seq / type / text / prompt / completion
        when(writer.append(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    com.agenthub.runtime.domain.model.RunEvent event =
                            new com.agenthub.runtime.domain.model.RunEvent();
                    event.setEventType(((com.agenthub.runtime.domain.model.EventType) invocation.getArgument(2)).code());
                    return event;
                });

        var resp = service.approvePlan(7L);
        // Agent 被删：本轮失败但要如实回 FAILED，不能假装执行过
        assertTrue("FAILED".equals(resp.getStatus()));
        verify(planRunner, never()).execute(any(), any(), any());
        // 失败后必须释放单飞，否则该会话再也发不出下一轮
        verify(flight).finish(7L);
    }

    private com.agenthub.runtime.domain.model.RunSession session() {
        com.agenthub.runtime.domain.model.RunSession session = new com.agenthub.runtime.domain.model.RunSession();
        session.setId(7L);
        session.setAgentId(9L);
        session.setUserId(100L);
        session.setStatus("RUNNING");
        return session;
    }

    /** 供将来扩展：确认新增模式确实走 Plan 执行器而不是 ReAct。 */
    static AgentDef planAgent() {
        AgentDef agent = new AgentDef();
        agent.setId(9L);
        agent.setName("计划助手");
        agent.setRunMode(AgentRunMode.PLAN_AND_EXECUTE.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }
}
