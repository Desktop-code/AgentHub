package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 串行模板的前置摘要拼装与截断。所属域：runtime。
 *
 * <p>只测纯函数：本步问题必带、失败占位、单步 2000 / 累计 6000 字符硬顶。
 * 固定串行与路由后串行共用本类，区别只在调用方传入的「本步问题」是什么。
 */
class FixedSequenceContextTest {

    @Test
    void firstStepOnlyCarriesUserQuestion() {
        // 第 1 步没有前置：只给用户原问，不凭空造摘要区
        assertEquals("用户原问", FixedSequenceContext.render("用户原问", List.of()));
        assertFalse(FixedSequenceContext.render("用户原问", null).contains("前置专家结论"));
    }

    @Test
    void laterStepSeesUserQuestionAndPriorAnswers() {
        String text = FixedSequenceContext.render("用户原问", List.of(
                new FixedSequenceContext.PriorStep("固定串行 1/2 · 子A", "第一步答案", false),
                new FixedSequenceContext.PriorStep("固定串行 2/2 · 子B", null, true)));
        assertTrue(text.startsWith("本轮问题：\n用户原问"), text);
        assertTrue(text.contains("【前置专家结论】"), text);
        assertTrue(text.contains("第一步答案"), text);
        // 失败步用中文占位，不把异常原文或空串塞给下一步
        assertTrue(text.contains("（该步未完成）"), text);
    }

    @Test
    void routerThenSequenceStepCarriesSubQuery() {
        // 路由后串行：本步问题是判路由切好的 sub_query，不是整段合问
        String text = FixedSequenceContext.render("子问B", List.of(
                new FixedSequenceContext.PriorStep("路由后串行 1/2 · 子A", "第一步答案", false)));
        assertTrue(text.startsWith("本轮问题：\n子问B"), text);
        // 标题与正文都不该写回「用户原问」：串行模板必须尊重判路由的切分
        assertFalse(text.contains("用户原问"), text);
        assertTrue(text.contains("第一步答案"), text);
    }

    @Test
    void singleStepAnswerIsTruncated() {
        String longAnswer = "甲".repeat(FixedSequenceContext.SINGLE_STEP_LIMIT + 500);
        String text = FixedSequenceContext.render("问", List.of(
                new FixedSequenceContext.PriorStep("子A", longAnswer, false)));
        assertTrue(text.contains("…"), "超长单步摘要应打点截断");
        // 截断后摘要区不应再出现超限长度
        assertTrue(text.length() < FixedSequenceContext.SINGLE_STEP_LIMIT + 200, String.valueOf(text.length()));
    }

    @Test
    void totalPriorSummaryIsCapped() {
        // 5 步各 2000 字符：累计必须被 6000 硬顶拦住，后面的步骤不再往里堆
        List<FixedSequenceContext.PriorStep> prior = List.of(
                new FixedSequenceContext.PriorStep("子1", "乙".repeat(2000), false),
                new FixedSequenceContext.PriorStep("子2", "丙".repeat(2000), false),
                new FixedSequenceContext.PriorStep("子3", "丁".repeat(2000), false),
                new FixedSequenceContext.PriorStep("子4", "戊".repeat(2000), false),
                new FixedSequenceContext.PriorStep("子5", "己".repeat(2000), false));
        String text = FixedSequenceContext.render("问", prior);
        int priorStart = text.indexOf("【前置专家结论】");
        String priorBlock = text.substring(priorStart);
        // 允许固定的标题行与省略号，但正文不得越过累计上限
        assertTrue(priorBlock.length() <= FixedSequenceContext.TOTAL_PRIOR_LIMIT + 200,
                String.valueOf(priorBlock.length()));
        assertTrue(priorBlock.contains("…"), "超长累计摘要应打点截断");
        // 超上限后不再追加后续步骤
        assertFalse(priorBlock.contains("第 5 步"), priorBlock.substring(Math.max(0, priorBlock.length() - 80)));
    }
}
