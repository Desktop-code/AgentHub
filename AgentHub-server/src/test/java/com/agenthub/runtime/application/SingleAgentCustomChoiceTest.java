package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.WorkbenchChoiceJson;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.VisionOcrExecutor;
import com.agenthub.runtime.infra.storage.TrialUserFileStorage;
import com.agenthub.runtime.infra.storage.TrialUserImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单 Agent「其它 / 自填」的续作用例。所属域：runtime 的 application。
 *
 * <p>钉住本刀要修的现象：用户在单 Agent 选项里点「其它，我自己说」并写下内容后，
 * 这句答复<b>不能</b>当成新问题重新走入口判路由，而应走 {@code /choice} 的 {@code text} 通道，
 * 由同一位专家接着跑：
 * <ol>
 *   <li>模型入参用用户自己写下的原文（不是平台行文案「其它，我自己说」）；</li>
 *   <li>USER 事件回执也是用户原文；</li>
 *   <li>入口路由（{@code router.execute}）一次都不调。</li>
 * </ol>
 *
 * <p>边界：全部 mock，不连库、不出站、不打模型；只验证 service 的编排与载荷。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleAgentCustomChoiceTest {

    /** 用例会话主键 */
    private static final long SESSION_ID = 21L;
    /** 上一轮作答的专家（sticky），续作必须还是它 */
    private static final long AGENT_ID = 33L;
    /** 当前平台用户 */
    private static final long USER_ID = 100L;
    /** 用户自己写下的答复原文：既是模型入参，也是 USER 事件回执 */
    private static final String CUSTOM_REPLY = "就用 Spring Boot 3 + JDK 17，数据库继续 MySQL";

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
    @Mock
    private WorkflowReplyResumer workflowReplies;
    @Mock
    private MaReplyResumer maReplies;

    private TrialRunAppService service;

    @BeforeEach
    void setUp() {
        service = new TrialRunAppService(sessions, events, agents, access, loader, react, router, planRunner,
                new PlanPendingRegistry(), new ChildPlanApprovalRegistry(), writer, flight, operatorContext,
                operLogWriter, obs, memory, new TrialLiveBuffer(), vision, titleGenerator, maSpecs, idempotency,
                reception, new WorkbenchRouteForceRegistry(), org.mockito.Mockito.mock(StaleRunCloser.class),
                org.mockito.Mockito.mock(TrialUserImageStorage.class),
                org.mockito.Mockito.mock(TrialUserFileStorage.class),
                org.mockito.Mockito.mock(TrialAttachmentReader.class),
                org.mockito.Mockito.mock(EntryPreprocess.class),
                workflowReplies, maReplies);

        when(access.require(SESSION_ID)).thenReturn(runningSession());
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(agent()));
        when(events.listBySessionId(SESSION_ID)).thenReturn(previousTurnWithChoice());
        // 下一条事件序号固定为 4：USER 回执从 4 起，续作正文接在后面
        when(events.nextSeq(SESSION_ID)).thenReturn(4);
        when(writer.append(anyLong(), anyInt(), any(EventType.class), any(), any(), any()))
                .thenAnswer(SingleAgentCustomChoiceTest::toEvent);
        when(writer.append(anyLong(), anyInt(), any(EventType.class), any(), any(), any(), any()))
                .thenAnswer(SingleAgentCustomChoiceTest::toEvent);
        when(flight.tryStart(SESSION_ID)).thenReturn(true);
        when(operatorContext.requireUserId()).thenReturn(USER_ID);
        when(memory.recallDurable(any(RunSession.class), anyString()))
                .thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        when(loader.loadReact(any(AgentDef.class), anyString(), any(), anyBoolean())).thenReturn(new TrialPlan());
        when(react.execute(any(TrialPlan.class), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(turn("接着你写的方向，完整方案如下…"));
    }

    /**
     * 自填答复：走 text 通道回到同一位专家，不重新判路由，回执是用户原文。
     */
    @Test
    @DisplayName("单 Agent 自填：用用户原文续作，入口路由不参与")
    void customAnswerContinuesSameAgentWithoutRerouting() {
        service.choose(SESSION_ID, "", CUSTOM_REPLY);

        // 1) 模型拿到的就是用户原文，而不是平台行文案「其它，我自己说」
        ArgumentCaptor<String> modelUser = ArgumentCaptor.forClass(String.class);
        verify(react).execute(any(TrialPlan.class), modelUser.capture(), eq(USER_ID), eq(SESSION_ID), any());
        assertTrue(modelUser.getValue().contains(CUSTOM_REPLY),
                "续作入参必须带上用户自己写下的答复：" + modelUser.getValue());
        assertFalse(modelUser.getValue().contains("其它，我自己说"),
                "平台自填行文案不能当成答复喂给模型：" + modelUser.getValue());
        // 上一轮问句也在入参里：否则专家只看到一句孤立答复
        assertTrue(modelUser.getValue().contains("请选一种实现语言"), modelUser.getValue());

        // 2) USER 事件回执是用户原文（不是「其它，我自己说」）
        verify(writer).append(eq(SESSION_ID), eq(4), eq(EventType.USER), eq(CUSTOM_REPLY), isNull(), isNull());

        // 3) 没有重新走入口判路由：这正是修复前「被当成新问题」的根因
        verify(router, never()).execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any());
        // 4) 仍然只读同一位专家（sticky agent），不另挑人
        verify(agents).findById(AGENT_ID);
    }

    /**
     * 普通点选回归：不传 text 时仍按 optionId 命中，回执用服务端登记的选项文案。
     */
    @Test
    @DisplayName("普通点选回归：仍按 optionId 命中，回执是选项文案")
    void plainChoiceStillUsesRegisteredLabel() {
        service.choose(SESSION_ID, "spring", null);

        verify(writer).append(eq(SESSION_ID), eq(4), eq(EventType.USER), eq("Spring Boot 3"), isNull(), isNull());
        verify(router, never()).execute(any(), any(), anyString(), anyString(), anyLong(), anyLong(), any(), any());
    }

    /**
     * 既没选项也没文本：明确拒绝，不写任何事件、不占单飞。
     */
    @Test
    @DisplayName("空载荷：直接拒绝，不写事件")
    void emptyPayloadRejected() {
        try {
            service.choose(SESSION_ID, "", "  ");
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage().contains("请选择一个选项"), ex.getMessage());
        }
        verify(flight, never()).tryStart(anyLong());
        verify(react, never()).execute(any(TrialPlan.class), anyString(), anyLong(), anyLong(), any());
    }

    /** 运行中的工作台会话：有 sticky 专家，可续作。 */
    private static RunSession runningSession() {
        RunSession session = new RunSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStickyAgentId(AGENT_ID);
        session.setStickyRouteKey("backend");
        session.setEntryChannel("WORKBENCH");
        session.setStatus("RUNNING");
        session.setMessageCount(2);
        return session;
    }

    /** 上一轮作答的专家：启用中的单专家 ReAct。 */
    private static AgentDef agent() {
        AgentDef agent = new AgentDef();
        agent.setId(AGENT_ID);
        agent.setName("后端专家");
        agent.setRunMode(AgentRunMode.REACT.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    /**
     * 上一轮落库：用户问句 → 助手正文 → 结构化选项。
     *
     * <p>选项块没有 waiting（单 Agent 普通拍板），平台自填行也不在 options 里。
     */
    private static List<RunEvent> previousTurnWithChoice() {
        RunEvent question = event(1, EventType.USER, "请选一种实现语言");
        RunEvent answer = event(2, EventType.ASSISTANT, "先确认实现语言，再给完整方案。");
        AgentChoice choice = new AgentChoice("请选一种实现语言", List.of(
                new AgentChoice.Option("spring", "Spring Boot 3"),
                new AgentChoice.Option("vue3", "Vue3 + Vite")));
        RunEvent choiceEvent = event(3, EventType.CHOICE, WorkbenchChoiceJson.write(choice));
        return List.of(question, answer, choiceEvent);
    }

    private static RunEvent event(int seq, EventType type, String content) {
        RunEvent event = new RunEvent();
        event.setSessionId(SESSION_ID);
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setContentText(content);
        return event;
    }

    /** 续作终答：有正文即可，工具 / 思考留空。 */
    private static TrialTurn turn(String answer) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(answer);
        return turn;
    }

    /** 落库调用 → 最小事件：只保留响应要拼的字段。 */
    private static RunEvent toEvent(org.mockito.invocation.InvocationOnMock invocation) {
        RunEvent event = new RunEvent();
        event.setSessionId(invocation.getArgument(0));
        event.setSeq(invocation.getArgument(1));
        event.setEventType(((EventType) invocation.getArgument(2)).code());
        return event;
    }
}
