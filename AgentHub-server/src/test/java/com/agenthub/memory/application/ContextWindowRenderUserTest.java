package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.WindowState;
import com.agenthub.memory.domain.service.ContextWindowStore;
import com.agenthub.memory.domain.service.SessionTurnSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 用户侧正文的渲染与去重。所属域：memory 的 application 单测。
 * <p>
 * 验收口径（P1）：
 * <ol>
 *   <li>摘要里已有的内容不得再出现在【最近对话】里（同一份内容不付两次费）；</li>
 *   <li>未覆盖的近轮必须保留（去重不能把新内容一起丢掉）；</li>
 *   <li>当前用户话永远在最后一段，且不与近轮重复；</li>
 *   <li>无摘要时行为与以前一致（不做任何删减）。</li>
 * </ol>
 * 不连 Redis、不连库、不出站模型。
 */
@ExtendWith(MockitoExtension.class)
class ContextWindowRenderUserTest {

    /** 长到足以参与判重的旧轮（判重阈值是 24 字） */
    private static final String COVERED_TURN = "用户问北京明天的天气，我查了气象接口返回晴 26 度，并提醒带伞。";
    private static final String FRESH_TURN = "用户接着问后天呢，我说明天再看更准。";

    @Mock
    private ContextWindowStore store;
    @Mock
    private SessionTurnSource events;
    @Mock
    private RollingSummaryService summaries;

    private ContextWindowFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ContextWindowFacade(store, events, summaries);
    }

    /**
     * 摘要覆盖了早期轮次：那些轮次不得再出现在【最近对话】里。
     */
    @Test
    void coveredTurnsAreOmittedFromRecent() {
        WindowState state = new WindowState();
        state.setSummary("早前小结：\n" + COVERED_TURN + "\n还确认了出行时间。");
        state.setTurns(new ArrayList<>(List.of(
                ContextTurn.of("user", COVERED_TURN),
                ContextTurn.of("assistant", "已按西湖区口径查询。"),
                ContextTurn.of("user", "那后天呢"))));

        String text = facade.renderUser(state, "那后天呢", List.of());

        // 摘要段里有这段，近轮段里不能再有第二遍
        assertTrue(text.contains("【会话摘要】"));
        int summarySpan = text.indexOf(COVERED_TURN);
        int recentSpan = text.indexOf("【最近对话】");
        assertTrue(summarySpan >= 0, "摘要里应保留该内容");
        if (recentSpan >= 0) {
            String recentBody = text.substring(recentSpan);
            assertFalse(recentBody.contains(COVERED_TURN), "近轮不得重复摘要已写过的内容");
        }
    }

    /**
     * 未被摘要覆盖的近轮必须保留：去重不能误删新内容。
     */
    @Test
    void uncoveredTurnsSurviveDedup() {
        WindowState state = new WindowState();
        state.setSummary("早前小结：\n" + COVERED_TURN);
        state.setTurns(new ArrayList<>(List.of(
                ContextTurn.of("user", COVERED_TURN),
                ContextTurn.of("user", FRESH_TURN),
                ContextTurn.of("user", "现在的问题"))));

        String text = facade.renderUser(state, "现在的问题", List.of());

        assertTrue(text.contains(FRESH_TURN), "未被摘要覆盖的近轮必须保留");
        assertTrue(text.contains("【当前用户】\n现在的问题"));
    }

    /**
     * 全部近轮都被摘要覆盖：整段【最近对话】不输出（连段头也不留）。
     */
    @Test
    void fullyCoveredWindowOmitsRecentSectionEntirely() {
        WindowState state = new WindowState();
        state.setSummary("小结：" + COVERED_TURN);
        state.setTurns(new ArrayList<>(List.of(
                ContextTurn.of("user", COVERED_TURN),
                ContextTurn.of("user", "本轮"))));

        String text = facade.renderUser(state, "本轮", List.of());

        assertFalse(text.contains("【最近对话】"), "整段被覆盖时不该留一个空的段头");
        assertTrue(text.contains("【会话摘要】"));
        assertTrue(text.contains("【当前用户】\n本轮"));
    }

    /**
     * 无摘要：不做任何去重，行为与改动前一致。
     */
    @Test
    void withoutSummaryNothingIsDropped() {
        WindowState state = new WindowState();
        state.setTurns(new ArrayList<>(List.of(
                ContextTurn.of("user", FRESH_TURN),
                ContextTurn.of("user", "本轮"))));

        String text = facade.renderUser(state, "本轮", List.of());

        assertFalse(text.contains("【会话摘要】"));
        assertTrue(text.contains("【最近对话】"));
        assertTrue(text.contains(FRESH_TURN));
    }

    /**
     * 当前用户话永远在最后一段，且不重复出现在近轮里。
     */
    @Test
    void currentTurnAppearsOnceAtTheEnd() {
        WindowState state = new WindowState();
        state.setTurns(new ArrayList<>(List.of(
                ContextTurn.of("user", FRESH_TURN),
                ContextTurn.of("user", "本轮问题"))));

        String text = facade.renderUser(state, "本轮问题", List.of());

        String marker = "【当前用户】\n";
        int at = text.indexOf(marker);
        assertTrue(at > 0);
        assertTrue(text.substring(at + marker.length()).startsWith("本轮问题"), "当前用户话必须在末尾段");
        assertFalse(text.substring(0, at).contains("本轮问题"), "近轮里不得重复当前用户话");
    }

    /**
     * 分段计量：摘要 / 近轮 / 短期 / 本轮四段都能被切出来。
     */
    @Test
    void sectionsAreSplittableForMetering() {
        WindowState state = new WindowState();
        state.setSummary("小结：" + COVERED_TURN);
        state.setTurns(new ArrayList<>(List.of(ContextTurn.of("user", "本轮"))));

        String text = facade.renderUser(state, "本轮",
                List.of(shortItem("常住城市", "杭州")));

        com.agenthub.runtime.domain.service.UserPromptSections.Parts parts =
                com.agenthub.runtime.domain.service.UserPromptSections.split(text);
        assertTrue(parts.summary() > 0, "摘要段应被切出");
        assertTrue(parts.shorts() > 0, "短期记忆段应被切出");
        assertTrue(parts.current() > 0, "本轮段应被切出");
        assertTrue(parts.total() >= parts.summary() + parts.shorts() + parts.current());
    }

    private static com.agenthub.memory.domain.model.MemoryItem shortItem(String title, String content) {
        com.agenthub.memory.domain.model.MemoryItem item = new com.agenthub.memory.domain.model.MemoryItem();
        item.setTitle(title);
        item.setContent(content);
        item.setStatus(1);
        return item;
    }
}
