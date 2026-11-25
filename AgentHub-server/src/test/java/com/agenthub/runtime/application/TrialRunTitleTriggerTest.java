package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.VisionOcrExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动会话标题的触发时机与写入口径。所属域：runtime。
 *
 * <p>现网口径（自 {@code fix(runtime): 会话标题改为首轮结束后的异步摘要} 起）：
 * <ol>
 *   <li>首轮成功后把整轮问答交给 {@link SessionTitleGeneratorAppService} 异步摘要标题，
 *       应用服务不再用首问截断抢先写 {@code session_title}（抢先写会让异步摘要因「已有值」永久跳过）；</li>
 *   <li>绑根与会话虚拟根一致：只依赖会话行，agentId 为空也照常提交，由生成器走平台摘要槽；</li>
 *   <li>第二次及以后的轮次不再提交；待批准本轮没有助手正文，标题推迟到批准执行后再生成；</li>
 *   <li>用户手改由仓储的条件更新保护（{@code title_source=MANUAL} 时命中 0 行），本类不覆盖。</li>
 * </ol>
 * 只测编排与提交时机，不连库、不出站模型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrialRunTitleTriggerTest {

    private static final long SESSION_ID = 7L;
    private static final long AGENT_ID = 9L;
    private static final long USER_ID = 100L;
    private static final long MA_ID = 31L;

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
    private com.agenthub.agent.application.ReceptionSchemeResolver reception;

    private TrialRunAppService service;

    @BeforeEach
    void setUp() {
        service = new TrialRunAppService(sessions, events, agents, access, loader, react, router, planRunner,
                new PlanPendingRegistry(), new ChildPlanApprovalRegistry(), writer, flight, operatorContext,
                operLogWriter, obs, memory, new TrialLiveBuffer(), vision, titleGenerator, maSpecs, idempotency,
                reception, new WorkbenchRouteForceRegistry(), org.mockito.Mockito.mock(StaleRunCloser.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserImageStorage.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserFileStorage.class),
                org.mockito.Mockito.mock(TrialAttachmentReader.class),
                org.mockito.Mockito.mock(EntryPreprocess.class),
                org.mockito.Mockito.mock(WorkflowReplyResumer.class),
                org.mockito.Mockito.mock(MaReplyResumer.class));
        // 无键请求：幂等闸门不托管，行为与改造前一致
        lenient().when(idempotency.begin(anyLong(), any(), any(), any())).thenReturn(com.agenthub.runtime.domain.model.TrialIdemGuard.none());
        lenient().when(operatorContext.requireUserId()).thenReturn(USER_ID);
        lenient().when(flight.tryStart(SESSION_ID)).thenReturn(true);
        lenient().when(agents.findById(AGENT_ID)).thenReturn(Optional.of(reactAgent()));
        lenient().when(events.nextSeq(SESSION_ID)).thenReturn(1);
        lenient().when(writer.append(anyLong(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(invocation -> event((EventType) invocation.getArgument(2)));
        lenient().when(memory.prepareUser(any(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        // 记忆段已在装配时进链（工具目录之前），因此桩的是 recallDurable 而不是旧的尾部追加方法
        lenient().when(memory.recallDurable(any(), any()))
                .thenAnswer(invocation -> new TrialMemoryCoordinator.Augmented("", java.util.List.of()));
        lenient().when(loader.loadReact(any(), any(), any())).thenReturn(new TrialPlan());
    }

    @Test
    void firstTurnTriggersAsyncTitleSummary() {
        stubSession(0, AGENT_ID);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("订单已发货"));

        service.send(SESSION_ID, "帮我查订单");

        // 首轮问答落库后交给后台摘要标题：本轮只提交任务，不等模型、不阻塞响应
        verify(titleGenerator).asyncGenerateTitle(SESSION_ID, AGENT_ID, "帮我查订单", "订单已发货");
        // 应用服务不得再用首问截断抢先写库，否则异步摘要会因「已有标题」永久跳过
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void secondTurnDoesNotTouchTitle() {
        stubSession(1, AGENT_ID);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("第二条回答"));

        service.send(SESSION_ID, "再问一句");

        // 标题首轮就定了；后续轮次既不写库也不调模型
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
        verify(titleGenerator, never()).asyncGenerateTitle(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void virtualRootFirstTurnTriggersTitleSummaryWithoutAgent() {
        stubSession(0, null);
        AgentRuntimeConfig runtime = new AgentRuntimeConfig();
        runtime.setMaTemplate("ROUTER_PARALLEL");
        MaRunSpec spec = new MaRunSpec(MA_ID, "triage", "分诊方案", "ROUTER_PARALLEL",
                runtime, java.util.List.of(), null, null);
        when(maSpecs.resolve(any(), any())).thenReturn(spec);
        when(router.execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(new RouterTrialExecutor.RoutedTurn("", "", java.util.List.of(),
                        java.util.List.of(), turn("方案已执行"), null));

        service.send(SESSION_ID, "先把候选理一遍");

        // 虚拟根没有 Agent 也要提交标题生成：agentId 传空，由生成器走平台摘要槽
        verify(titleGenerator).asyncGenerateTitle(eq(SESSION_ID), isNull(), eq("先把候选理一遍"), eq("方案已执行"));
    }

    @Test
    void pendingPlanTurnDefersTitleUntilApproved() {
        stubSession(0, AGENT_ID);
        TrialTurn waiting = new TrialTurn();
        waiting.setAssistantSuppressed(true);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(waiting);

        MessageResp resp = service.send(SESSION_ID, "先出个计划");

        // 待批准本轮没有助手正文：标题摘要推迟到批准执行后再做，本轮不写库也不出站
        assertTrue(resp.isPlanPending(), String.valueOf(resp));
        verify(titleGenerator, never()).asyncGenerateTitle(anyLong(), anyLong(), anyString(), anyString());
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void failedTurnDoesNotWriteTitle() {
        stubSession(0, AGENT_ID);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new com.agenthub.runtime.domain.service.TrialFailure("模型调用失败"));

        service.send(SESSION_ID, "帮我查订单");

        // 本轮失败：不写标题、不花出站
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
        verify(titleGenerator, never()).asyncGenerateTitle(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void blankFirstMessageFallsBackToLlmTitle() {
        stubSession(0, AGENT_ID);
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("订单已发货"));

        // 直连 service 时没有 @NotBlank 校验兜底：原文空白 → 截不出标题 → 走既有 LLM 兜底
        service.send(SESSION_ID, "   ");

        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
        verify(titleGenerator).asyncGenerateTitle(eq(SESSION_ID), eq(AGENT_ID), anyString(), anyString());
    }

    /** 会话行：agentId 为空即虚拟根会话（需带 maDefId 才能被方案读源接受）。 */
    private void stubSession(int messageCount, Long agentId) {
        RunSession session = new RunSession();
        session.setId(SESSION_ID);
        session.setAgentId(agentId);
        session.setMaDefId(agentId == null ? MA_ID : null);
        session.setUserId(USER_ID);
        session.setRunMode(agentId == null ? "ROUTER" : "REACT");
        session.setStatus("RUNNING");
        session.setMessageCount(messageCount);
        when(access.require(SESSION_ID)).thenReturn(session);
    }

    private static RunEvent event(EventType type) {
        RunEvent event = new RunEvent();
        event.setSessionId(SESSION_ID);
        event.setEventType(type.code());
        return event;
    }

    private static TrialTurn turn(String answer) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(answer);
        return turn;
    }

    private static AgentDef reactAgent() {
        AgentDef agent = new AgentDef();
        agent.setId(AGENT_ID);
        agent.setName("演示");
        agent.setRunMode("REACT");
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }
}
