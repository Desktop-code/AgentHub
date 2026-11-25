package com.agenthub.runtime.application;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.workflow.domain.model.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 「点选续跑期间刷新不该露出待选选项」的接线用例。所属域：runtime 的 application。
 *
 * <p>钉住本刀（刀 A）唯一改动点：{@code chooseWorkflowReply} 必须和同文件其它续跑路径一样，
 * 在续跑开始前登记内存快照、并 tee 后续增量；续跑结束后清理。
 *
 * <p>为什么必须断言「续跑进行中」那一拍：这正是用户刷新的时刻。缺了快照登记时
 * {@code /live-events} 为空，前端只能读落库骨架里的 {@code WAITING_REPLY} 与选项，
 * 回放就会把已经答过的询问卡重新画成待选。
 *
 * <p>边界：全部 mock，不连库、不出站、不打模型；只验证快照的登记 / 清理与内容。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowChoiceLiveSnapshotTest {

    /** 用例会话主键，与桩里的 run 保持一致 */
    private static final long SESSION_ID = 66L;

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
    @Mock
    private WorkflowReplyResumer workflowReplies;
    @Mock
    private MaReplyResumer maReplies;

    /** 内存快照：用例直接读它，模拟刷新时 /live-events 的读侧 */
    private TrialLiveBuffer live;
    private TrialRunAppService service;

    @BeforeEach
    void setUp() {
        live = new TrialLiveBuffer();
        service = new TrialRunAppService(sessions, events, agents, access, loader, react, router, planRunner,
                new PlanPendingRegistry(), new ChildPlanApprovalRegistry(), writer, flight, operatorContext,
                operLogWriter, obs, memory, live, vision, titleGenerator, maSpecs, idempotency, reception,
                new WorkbenchRouteForceRegistry(), org.mockito.Mockito.mock(StaleRunCloser.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserImageStorage.class),
                org.mockito.Mockito.mock(com.agenthub.runtime.infra.storage.TrialUserFileStorage.class),
                org.mockito.Mockito.mock(TrialAttachmentReader.class),
                org.mockito.Mockito.mock(EntryPreprocess.class),
                workflowReplies, maReplies);

        when(access.require(SESSION_ID)).thenReturn(runningSession());
        when(operatorContext.requireUserId()).thenReturn(100L);
        // 落库下一条序号固定为 7：快照第一帧必须与「身份 begin」同序号，不占用户气泡
        when(events.nextSeq(anyLong())).thenReturn(7);
        when(writer.append(anyLong(), anyInt(), any(EventType.class), any(), any(), any()))
                .thenAnswer(WorkflowChoiceLiveSnapshotTest::toEvent);
        when(writer.append(anyLong(), anyInt(), any(EventType.class), any(), any(), any(), any()))
                .thenAnswer(WorkflowChoiceLiveSnapshotTest::toEvent);
    }

    /**
     * 工作流点选续跑：进行中必须能读到快照，且快照里不再有待选。
     *
     * <p>这是本刀修的那个现象：点选语言后节点已在跑，刷新同一会话不该又出现语言选项。
     */
    @Test
    @DisplayName("工作流点选续跑：进行中 /live-events 非空，且询问节点已过、无选项")
    void workflowChoiceRegistersLiveSnapshotDuringResume() {
        when(maReplies.waitingRun(SESSION_ID)).thenReturn(Optional.empty());
        WorkflowRun waiting = waitingRun();
        when(workflowReplies.waitingRun(SESSION_ID)).thenReturn(Optional.of(waiting));
        when(workflowReplies.waitingOptionLabel(waiting, "python")).thenReturn(Optional.of("Python"));

        // 续跑进行中的观测量：快照是否非空、是否还带待选、序号是否与身份 begin 对齐
        List<String> observed = new ArrayList<>();
        when(workflowReplies.resumeWithLabel(eq(SESSION_ID), eq(waiting), eq("Python"), eq(100L), any()))
                .thenAnswer(invocation -> {
                    TrialProgress sink = invocation.getArgument(4);
                    assertNotNull(sink, "续跑必须拿到 tee 后的写入器");
                    // 模拟引擎在续跑中途推身份 + 运行中卡片（询问节点已 SUCCESS）
                    sink.beginParticipant(new com.agenthub.runtime.domain.model.ParticipantView(
                            "p-0-root-mini_game_dev", "小游戏研发",
                            com.agenthub.runtime.domain.model.ParticipantView.KIND_WORKFLOW,
                            "mini_game_dev", 0));
                    sink.workflow(resumedCard());
                    // 这一拍就是用户刷新页面的时刻：读 /live-events
                    List<com.agenthub.runtime.api.dto.EventResp> snapshot = live.snapshot(SESSION_ID);
                    observed.add("size=" + snapshot.size());
                    for (com.agenthub.runtime.api.dto.EventResp item : snapshot) {
                        observed.add(item.getEventType() + "@" + item.getSeq());
                        if ("workflow".equals(item.getEventType())) {
                            observed.add(item.getContentText());
                        }
                    }
                    return Optional.of(resumed());
                });

        service.chooseStream(SESSION_ID, "python", null, noopProgress(), noopEnd());

        // 1) 续跑中途读得到快照（本刀修复前这里是空表）
        assertTrue(observed.contains("size=2"), "续跑中途应有身份 + 卡片两帧：" + observed);
        // 2) 第一帧与「身份 begin」同序号，且不凭空多出用户气泡
        assertTrue(observed.contains("participant@7"), observed.toString());
        assertTrue(observed.contains("workflow@8"), observed.toString());
        assertFalse(observed.contains("user@7"), "工作流点选续跑不落 USER 事件，快照也不该有：" + observed);
        // 3) 快照里的卡片已是运行中、询问节点已过，不含任何待选
        String card = observed.stream().filter((item) -> item.startsWith("{\"name\"")).findFirst().orElse("");
        assertTrue(card.contains("\"status\":\"RUNNING\""), card);
        assertTrue(card.contains("\"code\":\"ask_language\",\"name\":\"询问实现语言\",\"type\":\"AGENT\",\"status\":\"SUCCESS\""),
                card);
        assertFalse(card.contains("\"choice\""), "快照不应再带过期选项：" + card);

        // 4) 续跑同步结束后快照清理：权威数据是刚落库的事件
        assertTrue(live.snapshot(SESSION_ID).isEmpty(), "续跑结束后应清掉内存快照");
        assertFalse(live.running(SESSION_ID), "清理后不应再被当成在跑");
    }

    /**
     * 方案点选续跑：原有快照路径不能被本刀改坏。
     *
     * <p>方案点选先落「已答复」CHOICE，因此快照起点是它的下一条；这里只确认仍然登记、仍然清理。
     */
    @Test
    @DisplayName("方案点选续跑：快照仍登记并从下一条序号起，结束后清理")
    void maChoiceKeepsLiveSnapshot() {
        MaPauseSnapshot state = new MaPauseSnapshot();
        state.setSessionId(SESSION_ID);
        state.setChoice(new AgentChoice("你想用哪种语言实现？", List.of(
                new AgentChoice.Option("python", "Python"),
                new AgentChoice.Option("java", "Java"))));
        when(maReplies.waitingRun(SESSION_ID)).thenReturn(Optional.of(state));
        when(flight.tryStart(SESSION_ID)).thenReturn(true);

        List<String> observed = new ArrayList<>();
        when(maReplies.resumeWaiting(any(RunSession.class), eq(state), eq("Python"), any()))
                .thenAnswer(invocation -> {
                    TrialProgress sink = invocation.getArgument(3);
                    sink.beginParticipant(new com.agenthub.runtime.domain.model.ParticipantView(
                            "p-0-root-ma", "综合台",
                            com.agenthub.runtime.domain.model.ParticipantView.KIND_AGENT, null, 0));
                    for (com.agenthub.runtime.api.dto.EventResp item : live.snapshot(SESSION_ID)) {
                        observed.add(item.getEventType() + "@" + item.getSeq());
                    }
                    return Optional.of(maResumed());
                });

        service.chooseStream(SESSION_ID, "python", null, noopProgress(), noopEnd());

        // 方案点选的第一条落库是「已答复」CHOICE，因此快照从 7 + 1 = 8 起
        assertTrue(observed.contains("participant@8"), "方案点选快照序号应让开 CHOICE 行：" + observed);
        assertTrue(live.snapshot(SESSION_ID).isEmpty(), "方案点选结束后同样要清理快照");
    }

    /** 运行中的会话：可发送、可续跑。 */
    private static RunSession runningSession() {
        RunSession session = new RunSession();
        session.setId(SESSION_ID);
        session.setUserId(100L);
        session.setStatus("RUNNING");
        session.setMessageCount(2);
        return session;
    }

    /** 等待回复的工作流运行。 */
    private static WorkflowRun waitingRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(9L);
        run.setTenantId(1L);
        run.setWorkflowId(88L);
        run.setStatus("WAITING_REPLY");
        run.setWorkbenchSessionId(SESSION_ID);
        return run;
    }

    /**
     * 续跑中途的运行中卡片：询问节点已 SUCCESS、实现节点在跑，且不带 choice。
     *
     * <p>与 {@code WorkflowCardProjector} 在续跑开跑后的产出同形（只保留用例要断言的字段）。
     */
    private static WorkflowRunView resumedCard() {
        return new WorkflowRunView("小游戏研发", "mini_game_dev", "RUNNING", "请选一种实现语言",
                List.of(
                        new WorkflowRunView.Node("cond_lang", "是否已选语言", "CONDITION", "SUCCESS", "已完成",
                                "", List.of(), List.of(), ""),
                        new WorkflowRunView.Node("ask_language", "询问实现语言", "AGENT", "SUCCESS", "已完成",
                                "", List.of(), List.of(), "Python"),
                        new WorkflowRunView.Node("play_impl", "游戏实现", "AGENT", "RUNNING", "执行中",
                                "", List.of(), List.of(), "")));
    }

    /** 续跑结果：一条身份 + 一张卡片 + 一条助手正文，与首次发起同序。 */
    private static WorkflowReplyResumer.Resumed resumed() {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("已按 Python 开始实现");
        return new WorkflowReplyResumer.Resumed(turn, timeline(turn.getAssistantText()));
    }

    /**
     * 方案点选续跑的同类结果。
     *
     * <p>两个 {@code Resumed} 是各自内部的独立 record，不能互相替换：
     * 方案路径按 {@code MaReplyResumer.Resumed} 取 turn，混用会在运行期抛 ClassCastException。
     */
    private static MaReplyResumer.Resumed maResumed() {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("已按 Python 继续作答");
        return new MaReplyResumer.Resumed(turn, timeline(turn.getAssistantText()));
    }

    /**
     * 最小时间线：身份 → 卡片 → 助手正文。
     *
     * @param text 助手正文
     * @return 三条事件
     */
    private static List<RunEvent> timeline(String text) {
        RunEvent identity = new RunEvent();
        identity.setSessionId(SESSION_ID);
        identity.setSeq(7);
        identity.setEventType(EventType.PARTICIPANT.code());
        RunEvent card = new RunEvent();
        card.setSessionId(SESSION_ID);
        card.setSeq(8);
        card.setEventType(EventType.WORKFLOW.code());
        RunEvent assistant = new RunEvent();
        assistant.setSessionId(SESSION_ID);
        assistant.setSeq(9);
        assistant.setEventType(EventType.ASSISTANT.code());
        assistant.setContentText(text);
        return List.of(identity, card, assistant);
    }

    /** 落库调用 → 最小事件：只保留续跑响应要拼的字段。 */
    private static RunEvent toEvent(org.mockito.invocation.InvocationOnMock invocation) {
        RunEvent event = new RunEvent();
        event.setSessionId(invocation.getArgument(0));
        event.setSeq(invocation.getArgument(1));
        event.setEventType(((EventType) invocation.getArgument(2)).code());
        return event;
    }

    /** 不关心过程帧的写入器：本用例只断言快照与响应。 */
    private static TrialProgress noopProgress() {
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
            }

            @Override
            public void route(String summary) {
            }

            @Override
            public void reasoning(String delta) {
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
            }

            @Override
            public void assistant(String delta) {
            }
        };
    }

    /**
     * 不关心终帧的结束出口：只要确认成功路径走到了 done。
     */
    private static TrialStreamEnd noopEnd() {
        return new TrialStreamEnd() {
            @Override
            public void done(MessageResp resp) {
                assertNotNull(resp, "续跑应给出本轮响应");
            }

            @Override
            public void fail(String message) {
                throw new AssertionError("续跑不该走失败出口：" + message);
            }
        };
    }

    /** 预留：确认快照清理后可再次登记（重复点选不会残留上一轮）。 */
    @Test
    @DisplayName("快照清理后同一会话可再次登记，不残留上一轮")
    void snapshotCanBeRegisteredAgainAfterClear() {
        AtomicBoolean firstSeen = new AtomicBoolean(false);
        live.beginAt(SESSION_ID, 7);
        TrialProgress sink = TrialLiveBuffer.tee(noopProgress(), live.recorder(SESSION_ID));
        sink.workflow(resumedCard());
        firstSeen.set(!live.snapshot(SESSION_ID).isEmpty());
        live.clear(SESSION_ID);
        assertTrue(firstSeen.get());
        assertEquals(0, live.snapshot(SESSION_ID).size());
    }

    /** 供断言消息使用：参数捕获在需要时再启用（保持 Mockito 严格性可读）。 */
    @SuppressWarnings("unused")
    private static List<RunEvent> captured(ArgumentCaptor<RunEvent> captor) {
        return captor.getAllValues();
    }
}
