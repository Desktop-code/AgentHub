package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审环判定解析窄测。所属域：runtime。
 *
 * <p>核心口径：只有明确 {@code REVISE} 才回修；解析失败 / 未知值 / 空输出一律 ACCEPT，
 * 避免模型输出不听话时把有限轮次耗光甚至死循环。
 */
class CriticVerdictParserTest {

    @Test
    void acceptParsed() {
        CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(
                "{\"decision\":\"ACCEPT\",\"feedback\":\"\"}");
        assertTrue(verdict.parsed());
        assertFalse(verdict.revise());
        assertEquals(CriticVerdictParser.DECISION_ACCEPT, verdict.decision());
    }

    @Test
    void reviseKeepsFeedback() {
        CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(
                "评审结论如下：\n```json\n{\"decision\":\"REVISE\",\"feedback\":\"补充价格与时效\"}\n```");
        assertTrue(verdict.parsed());
        assertTrue(verdict.revise());
        assertEquals("补充价格与时效", verdict.feedbackOrEmpty());
    }

    @Test
    void decisionIsCaseInsensitive() {
        assertTrue(CriticVerdictParser.parse("{\"decision\":\"revise\",\"feedback\":\"改\"}").revise());
        assertFalse(CriticVerdictParser.parse("{\"decision\":\"accept\"}").revise());
    }

    @Test
    void reviseWithoutFeedbackFallsBackToFixedText() {
        CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(
                "{\"decision\":\"REVISE\",\"feedback\":\"   \"}");
        assertTrue(verdict.revise());
        assertEquals(CriticVerdictParser.DEFAULT_FEEDBACK, verdict.feedbackOrEmpty());
    }

    @Test
    void unknownDecisionTreatedAsAccept() {
        CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(
                "{\"decision\":\"REJECT\",\"feedback\":\"不行\"}");
        assertTrue(verdict.parsed());
        assertFalse(verdict.revise());
        assertEquals(CriticVerdictParser.DECISION_ACCEPT, verdict.decision());
    }

    @Test
    void unparsableOutputTreatedAsAccept() {
        for (String reply : new String[]{null, "", "我觉得还行", "REVISE", "{\"decision\":\"REVISE\""}) {
            CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(reply);
            assertFalse(verdict.parsed(), String.valueOf(reply));
            assertFalse(verdict.revise(), String.valueOf(reply));
            assertEquals(CriticVerdictParser.DECISION_ACCEPT, verdict.decision());
        }
    }

    @Test
    void verdictAliasFieldAccepted() {
        CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(
                "{\"verdict\":\"REVISE\",\"feedback\":\"再补一段\"}");
        assertTrue(verdict.revise());
        assertEquals("再补一段", verdict.feedbackOrEmpty());
    }
}
