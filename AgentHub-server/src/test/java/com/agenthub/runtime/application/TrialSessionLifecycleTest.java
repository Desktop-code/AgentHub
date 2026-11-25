package com.agenthub.runtime.application;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.LongExtractTriggers;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.TrialTurnOutcome;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import com.agenthub.workflow.application.WorkflowRunAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多轮不结案也不抽。清空不抽。删除 RUNNING 经 closer 批抽。显式记住和摘要顺带仍抽。
 */
@ExtendWith(MockitoExtension.class)
class TrialSessionLifecycleTest {

    @Mock
    private RunSessionRepository sessions;
    @Mock
    private RunEventRepository events;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private TrialAccess access;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private TrialFlight flight;
    @Mock
    private ReActTrialExecutor react;
    @Mock
    private TrialMemoryCoordinator memory;
    @Mock
    private TrialSessionCloser closer;
    @Mock
    private TrialBindingLoader loader;
    @Mock
    private MaTrialSpecLoader maSpecs;
    @Mock
    private MaDefRepository mas;
    @Mock
    private WorkflowRunAppService workflowRuns;
    @Mock
    private TrialObsHooks obs;

    private TrialSessionAppService service;

    @BeforeEach
    void setUp() {
        TrialProperties properties = new TrialProperties();
        TrialLiveBuffer liveBuffer = new TrialLiveBuffer();
        PlanPendingRegistry pendingPlans = new PlanPendingRegistry();
        ChildPlanApprovalRegistry childApprovals = new ChildPlanApprovalRegistry();
        TrialSessionQueryService query = new TrialSessionQueryService(sessions, events, agents, access,
                operatorContext, properties, liveBuffer, loader, mas, org.mockito.Mockito.mock(WorkflowCardRebuilder.class));
        TrialSessionLifecycleService lifecycle = new TrialSessionLifecycleService(sessions, events, agents, access,
                operatorContext, operLogWriter, flight, react, memory, closer, pendingPlans, childApprovals,
                maSpecs, query);
        service = new TrialSessionAppService(sessions, access, operatorContext, operLogWriter, flight, react,
                closer, liveBuffer, pendingPlans, childApprovals, workflowRuns,
                org.mockito.Mockito.mock(WorkflowReplyResumer.class), org.mockito.Mockito.mock(MaReplyResumer.class),
                obs, query, lifecycle);
    }

    @Test
    void twoTurnsStayOpen() {
        RunSession session = new RunSession();
        session.setStatus("SUCCESS");
        session.setEndTime(LocalDateTime.now());
        TrialTurnOutcome.keepOpen(session);
        TrialTurnOutcome.keepOpen(session);
        assertEquals("RUNNING", session.getStatus());
        assertNull(session.getEndTime());
    }

    @Test
    void clearDropsWindowWithoutExtract() {
        RunSession session = open(3L);
        when(access.require(3L)).thenReturn(session);
        when(agents.findById(4L)).thenReturn(Optional.empty());
        when(sessions.findById(3L)).thenReturn(Optional.of(session));
        service.clear(3L);
        verify(memory).invalidate(3L);
        verify(memory, never()).onClosed(any());
        verify(closer, never()).extractBeforeDelete(any());
    }

    @Test
    void deleteRunningExtractsButClosedDoesNot() {
        RunSession open = open(3L);
        when(access.require(3L)).thenReturn(open);
        service.remove(3L);
        verify(closer).extractBeforeDelete(open);
        verify(memory, never()).onClosed(any());
        verify(memory).invalidate(3L);
        verify(sessions).logicDelete(3L);

        RunSession closed = open(8L);
        closed.setStatus("SUCCESS");
        when(access.require(8L)).thenReturn(closed);
        service.remove(8L);
        verify(closer, never()).extractBeforeDelete(closed);
    }

    @Test
    void explicitAndSummaryStillExtractWhilePlainTurnDoesNot() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setEnableLongExtract(1);
        policy.setLongExtractOnExplicit(1);
        policy.setLongExtractOnSessionEnd(1);
        assertFalse(LongExtractTriggers.explicit(policy, "帮我查一下订单"));
        assertTrue(LongExtractTriggers.explicit(policy, "请记住我住在上海"));
        assertTrue(LongExtractTriggers.onSummary(policy));
        assertTrue(LongExtractTriggers.sessionEnd(policy));
    }

    private static RunSession open(long id) {
        RunSession session = new RunSession();
        session.setId(id);
        session.setAgentId(4L);
        session.setUserId(7L);
        session.setStatus("RUNNING");
        return session;
    }
}
