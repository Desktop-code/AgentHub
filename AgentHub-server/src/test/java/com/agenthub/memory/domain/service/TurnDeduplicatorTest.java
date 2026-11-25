package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.ContextTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要与近轮去重。所属域：memory 的领域单测。不连库、不连 Redis、不出站模型。
 * <p>
 * 验收口径：同一段内容不得同时出现在【会话摘要】与【最近对话】里；
 * 短句不判重（避免误删正常对话）；空白差异不影响判定（摘要模型会重排换行）。
 */
class TurnDeduplicatorTest {

    private static final String LONG_TURN = "用户问北京明天的天气，我查了气象接口返回晴 26 度，并提醒带伞。";

    /**
     * 摘要里逐字含近轮原文：必须判定为已覆盖。
     */
    @Test
    void exactSubstringIsCovered() {
        String summary = "早前对话要点：\n" + LONG_TURN + "\n还提到了出行时间。";
        assertTrue(TurnDeduplicator.covered(summary, LONG_TURN));
        assertTrue(TurnDeduplicator.blockCovered(summary, LONG_TURN));
    }

    /**
     * 摘要重排了换行与空格：去空白后仍是原文，必须判为覆盖。
     */
    @Test
    void whitespaceDifferencesStillCovered() {
        String summary = "早前对话要点：" + LONG_TURN.replace("，", "，\n") + "还提到了出行时间。";
        assertTrue(TurnDeduplicator.covered(summary, LONG_TURN));
    }

    /**
     * 摘要没写到这段：不得判重，否则会丢上下文。
     */
    @Test
    void unrelatedSummaryDoesNotCover() {
        String summary = "用户询问了发票开具流程，我说明了所需材料。";
        assertFalse(TurnDeduplicator.covered(summary, LONG_TURN));
    }

    /**
     * 短句不判重：正常对话节奏不该被当成重复。
     */
    @Test
    void shortTurnIsNeverDeduplicated() {
        assertFalse(TurnDeduplicator.covered("用户说好的，我回复已确认。可以继续。", "好的"));
        assertFalse(TurnDeduplicator.covered("可以", "可以"));
    }

    /**
     * 摘要为空时不做任何去重。
     */
    @Test
    void emptySummaryCoversNothing() {
        assertFalse(TurnDeduplicator.covered(null, LONG_TURN));
        assertFalse(TurnDeduplicator.covered("   ", LONG_TURN));
        assertFalse(TurnDeduplicator.blockCovered("", LONG_TURN));
    }

    /**
     * 逐条都不足以判重，但拼起来被摘要覆盖：整段必须省略。
     */
    @Test
    void blockCoveredByConcatenation() {
        String a = "请帮我把这批订单导出成表格";
        String b = "，再按月汇总一下销售额";
        String rendered = a + b;
        String summary = "用户要求：" + rendered + "。已确认口径。";
        // 单条都够长，逐条也能命中；这里额外验证整体判定不会漏
        assertTrue(TurnDeduplicator.blockCovered(summary, rendered));
    }

    /**
     * 只有部分轮次被覆盖时，未覆盖的仍要保留（逐条判定而非整段丢弃）。
     */
    @Test
    void partialOverlapKeepsOnlyUncoveredTurns() {
        String covered = "用户问北京明天的天气，我查了气象接口返回晴 26 度。";
        String fresh = "用户接着问后天呢，我还没查。";
        String summary = "早前：" + covered;
        List<ContextTurn> turns = List.of(ContextTurn.of("user", covered), ContextTurn.of("user", fresh));

        long kept = turns.stream()
                .filter(turn -> !TurnDeduplicator.covered(summary, turn.getText()))
                .count();
        assertEquals(1, kept, "只有未被摘要覆盖的那一轮应保留");
    }
}
