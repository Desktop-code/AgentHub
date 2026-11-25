package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import com.agenthub.workflow.application.WorkflowRunAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作台 / 调试入口渠道隔离。所属域：runtime。
 *
 * <p>两套列表共用 {@code agent_run_session}，但查询条件必须分别落到
 * DEBUG 与 WORKBENCH；建工作台会话时还要落 reception_ma_id 快照。
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchSessionChannelTest {

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
        properties.setHistoryLimit(20);
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

    /** 调试左栏只查 DEBUG，且仍按 agentId 过滤。 */
    @Test
    void debugPageQueriesOnlyDebugChannel() {
        when(operatorContext.requireUserId()).thenReturn(7L);
        when(sessions.page(any())).thenReturn(PageResult.of(0, 1, 20, List.of()));

        com.agenthub.runtime.api.dto.SessionPageReq req = new com.agenthub.runtime.api.dto.SessionPageReq();
        req.setAgentId(4L);
        req.setCurrent(1);
        req.setSize(20);
        service.page(req);

        SessionQuery query = captureQuery();
        assertEquals(EntryChannel.DEBUG.name(), query.getEntryChannel());
        assertEquals(7L, query.getUserId());
        assertEquals(4L, query.getAgentId());
    }

    /** 工作台左栏只查 WORKBENCH，不按 Agent / 方案过滤，换默认方案后历史仍可见。 */
    @Test
    void workbenchPageQueriesOnlyWorkbenchChannel() {
        when(operatorContext.requireUserId()).thenReturn(7L);
        when(sessions.page(any())).thenReturn(PageResult.of(0, 1, 20, List.of()));

        service.pageWorkbench(1, 20);

        SessionQuery query = captureQuery();
        assertEquals(EntryChannel.WORKBENCH.name(), query.getEntryChannel());
        assertEquals(7L, query.getUserId());
        assertNull(query.getAgentId());
        assertNull(query.getMaId());
    }

    /** 建工作台会话：渠道落 WORKBENCH，reception_ma_id 为默认方案快照，虚拟根 agent 为空。 */
    @Test
    void createForWorkbenchPersistsChannelAndReceptionSnapshot() {
        when(operatorContext.requireUserId()).thenReturn(7L);
        when(sessions.insert(any(), anyLong())).thenReturn(9L);
        RunSession stored = new RunSession();
        stored.setId(9L);
        stored.setUserId(7L);
        stored.setMaDefId(42L);
        stored.setRunMode("ROUTER");
        stored.setEntryChannel(EntryChannel.WORKBENCH.name());
        stored.setReceptionMaId(42L);
        when(sessions.findById(9L)).thenReturn(Optional.of(stored));
        when(mas.findById(42L)).thenReturn(Optional.empty());

        SessionResp resp = service.createForWorkbench(spec(42L));

        ArgumentCaptor<RunSession> captor = ArgumentCaptor.forClass(RunSession.class);
        verify(sessions).insert(captor.capture(), anyLong());
        RunSession inserted = captor.getValue();
        assertEquals(EntryChannel.WORKBENCH.name(), inserted.getEntryChannel());
        assertEquals(42L, inserted.getReceptionMaId());
        assertNull(inserted.getAgentId());
        assertEquals(EntryChannel.WORKBENCH.name(), resp.getEntryChannel());
        assertEquals(42L, resp.getReceptionMaId());
    }

    /** 取最后一次 page 的查询条件。 */
    private SessionQuery captureQuery() {
        ArgumentCaptor<SessionQuery> captor = ArgumentCaptor.forClass(SessionQuery.class);
        verify(sessions).page(captor.capture());
        return captor.getValue();
    }

    private static MaRunSpec spec(Long maId) {
        return new MaRunSpec(maId, "reception_default", "默认接待", "ROUTER_PARALLEL",
                new AgentRuntimeConfig(), List.of(), null, null);
    }
}
