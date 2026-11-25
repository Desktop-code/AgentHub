package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.runtime.application.router.ChildOutcome;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.MaRunWaitRepository;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 方案续跑剩余成员的身份来源。所属域：runtime 的测试。
 *
 * <p>回归「游戏实现及后续成员跑出方案内框」：续跑时 {@code MaNestedContext} 只包住
 * {@code resumeSerial}，退出后才组装时间线分段。若此时再按 intent 重算身份，
 * 深度会退化为 0、父键丢成 root，落库成 {@code p-0-root-*}。
 * 这里锁定：剩余成员的分段只认 {@link ChildOutcome#participant()} 那一份执行时身份。
 */
class MaReplyResumerResumeIdentityTest {

    private MaReplyResumer resumer;
    private RouterTrialExecutor router;
    private RunSession session;

    @BeforeEach
    void setUp() {
        TrialEventWriter writer = mock(TrialEventWriter.class);
        // 落库入口按调用参数回填类型与序号：断言只关心写了哪些帧、身份是什么
        when(writer.append(anyLong(), anyInt(), any(), anyString(), nullable(String.class),
                nullable(Integer.class), nullable(Integer.class)))
                .thenAnswer(invocation -> event(invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4)));
        when(writer.append(anyLong(), anyInt(), any(), anyString(),
                nullable(Integer.class), nullable(Integer.class)))
                .thenAnswer(invocation -> event(invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4)));

        MaRunWaitRepository waits = mock(MaRunWaitRepository.class);
        when(waits.markDone(anyLong())).thenReturn(true);
        RunEventRepository events = mock(RunEventRepository.class);
        when(events.nextSeq(anyLong())).thenReturn(1);
        router = mock(RouterTrialExecutor.class);
        MaTrialSpecLoader specs = mock(MaTrialSpecLoader.class);
        when(specs.requireForWorkbench(anyLong())).thenReturn(null);
        AgentDefRepository agents = mock(AgentDefRepository.class);
        ReceptionSchemeResolver reception = mock(ReceptionSchemeResolver.class);

        resumer = new MaReplyResumer(waits, writer, events, router, specs, agents, reception);

        session = new RunSession();
        session.setId(51L);
        session.setUserId(7L);
    }

    /**
     * 续跑一次跑到底：剩余成员落库身份必须是 {@code p-1-<方案键>-<叶子>}，
     * 且不能因为嵌套上下文已退出而退化成 {@code p-0-root-*}。
     */
    @Test
    void resumeKeepsExecutionTimeIdentityForRemainingMembers() {
        ParticipantView implement = ParticipantView.agent(
                "p-1-game_team-game_implement", "游戏实现", "game_implement", 1);
        ChildOutcome first = ChildOutcome.ok(
                        intent("game_play_design", "路由后串行 1/4 · 游戏玩法设计（game_play_design）"),
                        List.of(), turn("玩法设计完成"))
                .withParticipant(ParticipantView.agent(
                        "p-1-game_team-game_play_design", "游戏玩法设计", "game_play_design", 1));
        ChildOutcome remaining = ChildOutcome.ok(
                        intent("game_implement", "路由后串行 2/4 · 游戏实现（game_implement）"),
                        List.of(), turn("实现完成"))
                .withParticipant(implement);
        when(router.resumeSerial(nullable(AgentDef.class), nullable(MaRunSpec.class), any(), anyString(),
                anyLong(), anyLong(), nullable(TrialProgress.class)))
                .thenReturn(List.of(first, remaining));

        List<RunEvent> written = resumer.resumeWaiting(session, nestedState(), "HTML/CSS/JavaScript 单页", null)
                .orElseThrow()
                .events();

        RunEvent member = written.stream()
                .filter((item) -> EventType.PARTICIPANT.code().equals(item.getEventType()))
                .filter((item) -> "游戏实现".equals(item.getSummary()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("剩余成员必须补写一条带身份的开始行：" + summaries(written)));
        String payload = member.getContentText() == null ? "" : member.getContentText();
        assertTrue(payload.contains("\"id\":\"p-1-game_team-game_implement\""),
                "续跑剩余成员必须沿用执行时身份，实际=" + payload);
        assertEquals(-1, payload.indexOf("p-0-root-"), "不得再落到根层身份：" + payload);
    }

    /** 一层嵌套的停等快照：被停住的是第一位成员，剩余成员由 resumeSerial 返回。 */
    private static MaPauseSnapshot nestedState() {
        MaPauseSnapshot state = new MaPauseSnapshot();
        state.setMaDefId(9L);
        state.setSchemeName("游戏研发团队");
        state.setTemplate("ROUTER_THEN_SEQUENCE");
        state.setDepth(1);
        state.setParentKey("game_team");
        state.setUserText("帮我做个2048小游戏");
        state.setModelUser("帮我做个2048小游戏");
        return state;
    }

    /** 只用于取 routeLine 的意图；recovery 不应再从它重算身份。 */
    private static RoutedIntent intent(String routeKey, String routeLine) {
        return new RoutedIntent(null, routeKey, "固定串行", routeLine);
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }

    /** 构造一条落库事件；类型、序号、摘要与正文都取自调用参数。 */
    private static RunEvent event(int seq, EventType type, String summary, String content) {
        RunEvent event = new RunEvent();
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setSummary(summary);
        event.setContentText(content);
        return event;
    }

    private static List<String> summaries(List<RunEvent> events) {
        return events.stream().map(RunEvent::getSummary).toList();
    }
}
