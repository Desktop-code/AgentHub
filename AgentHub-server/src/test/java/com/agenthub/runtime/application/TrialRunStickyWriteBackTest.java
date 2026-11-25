package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.VisionOcrExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作台线路粘性的回写口径（刀3）。所属域：runtime。
 *
 * <p>验证「谁写、谁不写」：
 * <ol>
 *   <li>工作台 + 单意图成功轮 → 写本轮命中的 route_key / agentId；</li>
 *   <li>工作台 + 多意图轮 → 清空（null / null）；</li>
 *   <li>调试会话 → 一律不写，试跑自身语义不变；</li>
 *   <li>待批准（本轮没有终答）→ 不写；</li>
 *   <li>手动换线的一次性强制标记只作用一轮：第二次发送不再传 hint。</li>
 * </ol>
 * 只测回写编排，不连库、不出站模型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrialRunStickyWriteBackTest {

    private static final long SESSION_ID = 7L;
    private static final long AGENT_ID = 9L;
    private static final long USER_ID = 100L;
    private static final long MA_ID = 31L;
    private static final long CHILD_ID = 20L;

    @Mock
    private RunSessionRepository sessions;
    @Mock
    private RunEventRepository events;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private TrialAccess access;
    @Mock
    private TrialBindingLoader loader;
    @Mock
    private ReActTrialExecutor react;
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
    private VisionOcrExecutor vision;
    @Mock
    private SessionTitleGeneratorAppService titleGenerator;
    @Mock
    private MaTrialSpecLoader maSpecs;
    @Mock
    private TrialMessageIdempotency idempotency;
    @Mock
    private ReceptionSchemeResolver reception;

    private WorkbenchRouteForceRegistry routeForce;
    private TrialRunAppService service;

    @BeforeEach
    void setUp() {
        routeForce = new WorkbenchRouteForceRegistry();
        service = new TrialRunAppService(sessions, events, agents, access, loader, react, router, planRunner,
                new PlanPendingRegistry(), new ChildPlanApprovalRegistry(), writer, flight, operatorContext,
                operLogWriter, obs, memory, new TrialLiveBuffer(), vision, titleGenerator, maSpecs, idempotency,
                reception, routeForce, org.mockito.Mockito.mock(StaleRunCloser.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserImageStorage.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserFileStorage.class),
                org.mockito.Mockito.mock(TrialAttachmentReader.class),
                org.mockito.Mockito.mock(EntryPreprocess.class),
                org.mockito.Mockito.mock(WorkflowReplyResumer.class),
                org.mockito.Mockito.mock(MaReplyResumer.class));
        lenient().when(idempotency.begin(anyLong(), any(), any(), any()))
                .thenReturn(com.agenthub.runtime.domain.model.TrialIdemGuard.none());
        lenient().when(operatorContext.requireUserId()).thenReturn(USER_ID);
        lenient().when(flight.tryStart(SESSION_ID)).thenReturn(true);
        lenient().when(events.nextSeq(SESSION_ID)).thenReturn(1);
        lenient().when(writer.append(anyLong(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(invocation -> event((EventType) invocation.getArgument(2)));
        lenient().when(maSpecs.resolve(any(), any())).thenReturn(spec());
    }

    /** 工作台单意图成功轮：把命中的线路写回会话。 */
    @Test
    void workbenchSingleIntentWritesSticky() {
        stubSession("WORKBENCH", null);
        when(router.execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(routed(RouteSticky.keep("after_sale", CHILD_ID), turn("已处理")));

        service.send(SESSION_ID, "我要退款");

        verify(sessions).updateStickyRoute(SESSION_ID, "after_sale", CHILD_ID, USER_ID);
    }

    /** 工作台多意图轮：清空粘性，下一轮重新判。 */
    @Test
    void workbenchMultiIntentClearsSticky() {
        stubSession("WORKBENCH", null);
        when(router.execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(routed(RouteSticky.clear(), turn("两件事都办了")));

        service.send(SESSION_ID, "退款和订单一起看");

        verify(sessions).updateStickyRoute(SESSION_ID, null, null, USER_ID);
    }

    /** 调试会话：即使路由结果带粘性指令也不写，试跑语义不变。 */
    @Test
    void debugSessionNeverWritesSticky() {
        stubSession("DEBUG", AGENT_ID);
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(routerAgent()));
        when(router.execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(routed(RouteSticky.keep("after_sale", CHILD_ID), turn("调试完成")));

        service.send(SESSION_ID, "调试一句");

        verify(sessions, never()).updateStickyRoute(anyLong(), any(), any(), anyLong());
    }

    /** 待批准：本轮没有终答，不能提前把「已定线」落库。 */
    @Test
    void pendingApprovalDoesNotWriteSticky() {
        stubSession("WORKBENCH", null);
        TrialTurn waiting = new TrialTurn();
        waiting.setAssistantSuppressed(true);
        when(router.execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(routed(RouteSticky.keep("after_sale", CHILD_ID), waiting));

        service.send(SESSION_ID, "先出个计划");

        verify(sessions, never()).updateStickyRoute(anyLong(), any(), any(), anyLong());
    }

    /** 手动换线：强制标记只喂给下一轮，第二次发送回到普通沿用。 */
    @Test
    void forcedRouteAppliesToExactlyOneTurn() {
        stubSession("WORKBENCH", null);
        when(router.execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(routed(RouteSticky.keep("order", CHILD_ID), turn("已换线")));
        routeForce.put(SESSION_ID, "order");

        service.send(SESSION_ID, "第一轮");
        service.send(SESSION_ID, "第二轮");

        ArgumentCaptor<RouteHint> hints = ArgumentCaptor.forClass(RouteHint.class);
        verify(router, org.mockito.Mockito.times(2))
                .execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), hints.capture());
        // 第一轮：强制命中指定线路；第二轮：只剩普通沿用提示（会话刚写入 order）
        assertEquals("order", hints.getAllValues().get(0).forceRouteKey());
        assertNull(hints.getAllValues().get(1).forceRouteKey());
        assertEquals("order", hints.getAllValues().get(1).stickyRouteKey());
    }

    private void stubSession(String channel, Long agentId) {
        RunSession session = new RunSession();
        session.setId(SESSION_ID);
        session.setAgentId(agentId);
        session.setMaDefId(MA_ID);
        session.setEntryChannel(channel);
        session.setUserId(USER_ID);
        session.setRunMode(AgentRunMode.ROUTER.name());
        session.setStatus("RUNNING");
        session.setMessageCount(0);
        when(access.require(SESSION_ID)).thenReturn(session);
    }

    private AgentDef routerAgent() {
        AgentDef agent = new AgentDef();
        agent.setId(AGENT_ID);
        agent.setName("绑根路由器");
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    private MaRunSpec spec() {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        runtime.setMaTemplate("ROUTER_PARALLEL");
        return new MaRunSpec(MA_ID, "reception_default", "默认接待", "ROUTER_PARALLEL",
                runtime, List.of(), null, 99L);
    }

    private RouterTrialExecutor.RoutedTurn routed(RouteSticky sticky, TrialTurn turn) {
        return new RouterTrialExecutor.RoutedTurn("路由到…", null, List.of(), List.of(), turn, sticky);
    }

    private static TrialTurn turn(String answer) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(answer);
        return turn;
    }

    private static RunEvent event(EventType type) {
        RunEvent event = new RunEvent();
        event.setSessionId(SESSION_ID);
        event.setEventType(type.code());
        return event;
    }
}
