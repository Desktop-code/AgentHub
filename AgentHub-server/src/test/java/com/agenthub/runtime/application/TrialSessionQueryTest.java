package com.agenthub.runtime.application;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import com.agenthub.workflow.application.WorkflowRunAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话列表必须同时带上当前用户和 agentId，条数不超过配置。
 */
@ExtendWith(MockitoExtension.class)
class TrialSessionQueryTest {

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

    /**
     * 默认历史条数 20，用来验证更大的请求会被裁掉。
     */
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

    /**
     * 传了 size 就按 size（受全站上限 100 约束）；配置的 history-limit 只在未传 size 时兜底。
     */
    @Test
    void pageForcesCurrentUserAndAgent() {
        when(operatorContext.requireUserId()).thenReturn(7L);
        when(sessions.page(any())).thenAnswer(invocation -> {
            SessionQuery query = invocation.getArgument(0);
            assertEquals(7L, query.getUserId());
            assertEquals(4L, query.getAgentId());
            assertEquals(100L, query.getSize());
            return PageResult.of(0, 1, 20, List.of());
        });
        SessionPageReq req = new SessionPageReq();
        req.setAgentId(4L);
        req.setCurrent(1);
        req.setSize(100);
        assertEquals(0, service.page(req).getTotal());
        // 已取消懒结案：拉列表不再触发 closeIdle / 结案
        verify(closer, never()).sweep();
    }

    /**
     * 缺 agentId 时不查库。
     */
    @Test
    void pageRejectsMissingAgent() {
        SessionPageReq req = new SessionPageReq();
        BizException ex = assertThrows(BizException.class, () -> service.page(req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
