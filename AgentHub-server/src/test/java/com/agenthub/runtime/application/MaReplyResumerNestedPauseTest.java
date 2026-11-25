package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.MaRunWaitRepository;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 嵌套方案停等的事件补写顺序。所属域：runtime 的测试。
 *
 * <p>为什么这条顺序是硬约束：CHOICE 事件本身不带参与者身份，前端靠「它前面最后一条
 * 路由行」认人。补写必须排在成员段<b>之前</b>（成员卡才能认父），而「正在等待」的
 * CHOICE 必须排在成员段<b>之后</b>（否则会挂到入口 / 方案卡上）。
 *
 * <p>同时验证：入口卡按默认接待方案反查回来的 id 补写（{@code gw-ma-3}），
 * 方案卡 id 与直播同一算法（{@code p-0-root-game_team}）。
 */
class MaReplyResumerNestedPauseTest {

    private MaReplyResumer resumer;

    @BeforeEach
    void setUp() {
        TrialEventWriter writer = mock(TrialEventWriter.class);
        // 落库入口按调用参数回填类型与序号：断言只关心「写了哪些帧、先后如何」
        when(writer.append(anyLong(), anyInt(), any(), anyString(), nullable(String.class),
                nullable(Integer.class), nullable(Integer.class)))
                .thenAnswer(invocation -> event(invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3)));
        when(writer.append(anyLong(), anyInt(), any(), anyString(),
                nullable(Integer.class), nullable(Integer.class)))
                .thenAnswer(invocation -> event(invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3)));

        ReceptionSchemeResolver reception = mock(ReceptionSchemeResolver.class);
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(entry(), List.of(), true,
                null, null));

        resumer = new MaReplyResumer(mock(MaRunWaitRepository.class), writer, mock(RunEventRepository.class),
                mock(RouterTrialExecutor.class), mock(MaTrialSpecLoader.class), mock(AgentDefRepository.class),
                reception);
    }

    /**
     * 首轮停等：入口 → 方案 → 成员段 → CHOICE，顺序不可颠倒。
     */
    @Test
    void nestedPauseWritesGatewayAndSchemeBeforeMembersThenChoice() {
        List<RunEvent> written = new ArrayList<>();

        resumer.pauseRun(51L, nestedState(), 10, written);

        List<String> summaries = written.stream().map(RunEvent::getSummary).toList();
        int gateway = indexOfType(written, EventType.PARTICIPANT, "智能网关");
        int scheme = indexOfType(written, EventType.PARTICIPANT, "游戏研发团队");
        int member = indexOfType(written, EventType.PARTICIPANT, "游戏语言确认");
        int choice = indexOfType(written, EventType.CHOICE, null);

        assertTrue(gateway >= 0, "必须补写入口卡：" + summaries);
        assertTrue(scheme > gateway, "方案卡必须排在入口之后：" + summaries);
        assertTrue(member > scheme, "成员段必须排在方案卡之后，否则成员卡认不到父：" + summaries);
        assertTrue(choice > member, "等待中的 CHOICE 必须排在成员段之后，否则会挂错卡：" + summaries);
    }

    /**
     * 重复停等（已有已写键）：不再补入口 / 方案卡，避免同一轮出现两张方案卡。
     */
    @Test
    void repeatedPauseDoesNotRewriteCards() {
        MaPauseSnapshot state = nestedState();
        state.setPreWrittenKeys(new java.util.LinkedHashSet<>(List.of("game_ask_language")));
        List<RunEvent> written = new ArrayList<>();

        resumer.pauseRun(51L, state, 20, written);

        assertEquals(-1, indexOfType(written, EventType.PARTICIPANT, "智能网关"));
        assertEquals(-1, indexOfType(written, EventType.PARTICIPANT, "游戏研发团队"));
    }

    /** 默认接待方案：id=3 名称即入口卡展示名。 */
    private static MaDef entry() {
        MaDef def = new MaDef();
        def.setId(3L);
        def.setName("智能网关");
        return def;
    }

    /** 一层嵌套的停等快照：一位已完成，一位正在询问。 */
    private static MaPauseSnapshot nestedState() {
        MaPauseSnapshot state = new MaPauseSnapshot();
        state.setMaDefId(9L);
        state.setSchemeName("游戏研发团队");
        state.setTemplate("ROUTER_THEN_SEQUENCE");
        state.setDepth(1);
        state.setParentKey("game_team");
        state.setUserText("帮我做个2048小游戏");
        state.setModelUser("帮我做个2048小游戏");
        state.setChoice(AgentChoice.of("用哪种语言实现？", List.of(
                new AgentChoice.Option("java", "Java"),
                new AgentChoice.Option("ts", "TypeScript"))));
        MaPauseMember paused = new MaPauseMember();
        paused.setRouteKey("game_ask_language");
        paused.setParticipantId("p-1-game_team-game_ask_language");
        paused.setParticipantName("游戏语言确认");
        paused.setParticipantKind("agent");
        paused.setParticipantDepth(1);
        paused.setRouteLine("路由到 游戏语言确认（game_ask_language）");
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("请问用哪种语言实现？");
        paused.setTurn(turn);
        state.setPausedMember(paused);
        return state;
    }

    /** 构造一条落库事件；类型、序号与摘要都取自调用参数。 */
    private static RunEvent event(int seq, EventType type, String summary) {
        RunEvent event = new RunEvent();
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setSummary(summary);
        return event;
    }

    /**
     * 找第 index 条指定类型的事件；summary 非空时还要求摘要一致。
     *
     * @return 下标；没有时 -1
     */
    private static int indexOfType(List<RunEvent> events, EventType type, String summary) {
        for (int i = 0; i < events.size(); i++) {
            RunEvent one = events.get(i);
            if (!type.code().equals(one.getEventType())) {
                continue;
            }
            if (summary == null || summary.equals(one.getSummary())) {
                return i;
            }
        }
        return -1;
    }
}
