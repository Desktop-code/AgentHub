package com.agenthub.memory.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纠错信号识别：宁准勿滥。所属域：memory 的 domain。
 * <p>
 * 覆盖四件事：
 * <ol>
 *   <li>用户更正 / 确认要能被识别（否则同一 canonical 的覆盖根本不会发生）；</li>
 *   <li>问句与反问式否定不能被误认（否则每轮闲聊都多一次出站）；</li>
 *   <li>助手纠错独白能被识别并从批抽正文里整轮剔除；</li>
 *   <li>提示行只带事实键约束，不带任何用户正文。</li>
 * </ol>
 */
class MemoryCorrectionSignalTest {

    @Test
    void userCorrectionPhrasesDetected() {
        assertEquals(MemoryCorrectionSignal.Kind.CORRECTION,
                MemoryCorrectionSignal.detectUser("不是评估是苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CORRECTION,
                MemoryCorrectionSignal.detectUser("我说的是苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CORRECTION,
                MemoryCorrectionSignal.detectUser("刚打错了，是苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CORRECTION,
                MemoryCorrectionSignal.detectUser("纠正一下，我爱吃苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CORRECTION,
                MemoryCorrectionSignal.detectUser("刚才说错了").orElseThrow());
    }

    @Test
    void userConfirmationWithValueDetected() {
        assertEquals(MemoryCorrectionSignal.Kind.CONFIRMATION,
                MemoryCorrectionSignal.detectUser("对，是苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CONFIRMATION,
                MemoryCorrectionSignal.detectUser("嗯，就是苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CONFIRMATION,
                MemoryCorrectionSignal.detectUser("没错，是苹果").orElseThrow());
        assertEquals(MemoryCorrectionSignal.Kind.CONFIRMATION,
                MemoryCorrectionSignal.detectUser("对，我说的就是苹果").orElseThrow());
    }

    /** 纯应答没有具体值：不能触发抽取，否则每句「是的」都要多打一次模型 */
    @Test
    void bareAcknowledgementIsNotConfirmation() {
        assertTrue(MemoryCorrectionSignal.detectUser("是的").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser("好的").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser("嗯").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser("对").isEmpty());
    }

    /** 问句与反问不认：更正与确认都是陈述语气 */
    @Test
    void questionsAndRhetoricalNegationNotDetected() {
        assertTrue(MemoryCorrectionSignal.detectUser("不是评估是苹果吗").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser("不是吧").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser("不是吗").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser("今天天气怎么样？").isEmpty());
        assertTrue(MemoryCorrectionSignal.detectUser(null).isEmpty());
    }

    @Test
    void assistantCorrectionProposalDetected() {
        assertTrue(MemoryCorrectionSignal.isAssistantCorrection("你是想说苹果吗？"));
        assertTrue(MemoryCorrectionSignal.isAssistantCorrection("你是指喜欢吃苹果？"));
        assertTrue(MemoryCorrectionSignal.isAssistantCorrection("我的意思是把你说的「评估」更正一下"));
        assertFalse(MemoryCorrectionSignal.isAssistantCorrection("好的，已记录。"));
    }

    /**
     * 批抽正文剔除助手纠错轮：只留用户证据。
     * 这是「助手单方面纠正不写库」的落地点——模型看不到助手那句猜测，就抽不出那条偏好。
     */
    @Test
    void stripAssistantCorrectionsKeepsUserEvidenceOnly() {
        String transcript = "用户：我爱吃评估\n助手：你是想说苹果吗？\n";
        String cleaned = MemoryCorrectionSignal.stripAssistantCorrections(transcript);
        assertEquals("用户：我爱吃评估\n", cleaned);
        assertFalse(cleaned.contains("苹果"));
    }

    /** 用户自己说出来（含确认）的内容必须保留，不能被一起剔掉 */
    @Test
    void stripAssistantCorrectionsKeepsUserConfirmation() {
        String transcript = "用户：我爱吃评估\n助手：你是想说苹果吗？\n用户：对，是苹果\n助手：好的，已记录\n";
        String cleaned = MemoryCorrectionSignal.stripAssistantCorrections(transcript);
        assertTrue(cleaned.contains("用户：我爱吃评估"));
        assertTrue(cleaned.contains("用户：对，是苹果"), "用户确认是写库依据，必须保留");
        assertTrue(cleaned.contains("助手：好的，已记录"), "普通助手轮不影响记忆，原样保留");
        assertFalse(cleaned.contains("你是想说苹果吗"));
    }

    /** 多行助手正文：整轮剔除，不能只删首行留下残句 */
    @Test
    void stripAssistantCorrectionsDropsWholeMultiLineRound() {
        String transcript = "用户：我爱吃评估\n助手：你是想说苹果吗？\n如果是的话我就记下来。\n用户：对\n";
        String cleaned = MemoryCorrectionSignal.stripAssistantCorrections(transcript);
        assertFalse(cleaned.contains("如果是的话我就记下来"));
        assertTrue(cleaned.contains("用户：对"));
    }

    @Test
    void hintCarriesOnlyKeyInstruction() {
        assertTrue(MemoryCorrectionSignal.hint(null).isEmpty());
        String correction = MemoryCorrectionSignal.hint(MemoryCorrectionSignal.Kind.CORRECTION);
        assertTrue(correction.contains("fact_key"));
        assertTrue(correction.contains("【用户更正】"));
        String confirmation = MemoryCorrectionSignal.hint(MemoryCorrectionSignal.Kind.CONFIRMATION);
        assertTrue(confirmation.contains("【用户确认】"));
    }
}
