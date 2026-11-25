package com.agenthub.rag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TocNoiseFilterTest {

    @Test
    void recognizesTocTitles() {
        assertTrue(TocNoiseFilter.isTocTitle("目录"));
        assertTrue(TocNoiseFilter.isTocTitle("目 录"));
        assertTrue(TocNoiseFilter.isTocTitle("Contents"));
        assertTrue(TocNoiseFilter.isTocTitle("TABLE OF CONTENTS"));
        assertFalse(TocNoiseFilter.isTocTitle("考核规则"));
        assertFalse(TocNoiseFilter.isTocTitle("第一章 总则"));
    }

    @Test
    void recognizesTocLines() {
        assertTrue(TocNoiseFilter.isTocLine("第一章 总则 ........................ 1"));
        assertTrue(TocNoiseFilter.isTocLine("1.2 退货政策  12"));
        assertTrue(TocNoiseFilter.isTocLine("第二章 考勤 ······ 5"));
        assertTrue(TocNoiseFilter.isTocLine("感冒" + "\u3000\u3000" + "12"));
        assertTrue(TocNoiseFilter.isTocLine("观发识病" + "\u3000" + "3"));
        // 单半角空格短行也可能是目录「标题 页码」，但要避免误伤「用量 9」类字段——仅当标题侧无数字时
        assertTrue(TocNoiseFilter.isTocLine("视目诊病 5"));
        assertFalse(TocNoiseFilter.isTocLine("绩效考核按季度进行。分数由业绩态度协作组成。"));
        assertFalse(TocNoiseFilter.isTocLine("用量用法 9～30g"));
    }

    @Test
    void recognizesEbookNavLines() {
        assertTrue(TocNoiseFilter.isEbookNavLine("上一页：37．大黄生地茶"));
        assertTrue(TocNoiseFilter.isEbookNavLine("当前页：38．大黄枳芍茶"));
        assertTrue(TocNoiseFilter.isEbookNavLine("下一页：39．黄石茶"));
        assertTrue(TocNoiseFilter.isEbookNavLine("　返回目录：茶饮简介"));
        assertTrue(TocNoiseFilter.isTocLine("上一页：视口辨病"));
        assertFalse(TocNoiseFilter.isEbookNavLine("用途：热病伤津，口干烦渴。"));
    }

    @Test
    void detectsTocPage() {
        String tocPage = """
                目录
                第一章 总则 ........................ 1
                第二章 考勤 ........................ 5
                第三章 薪酬 ........................ 9
                """;
        assertTrue(TocNoiseFilter.isTocPage(tocPage));
        assertFalse(TocNoiseFilter.isTocPage("本手册适用于全体正式员工。入职须签署保密协议。"));
    }

    @Test
    void stripRemovesMarkdownTocBlock() {
        String md = """
                # 手册
                ## 目录
                第一章 ........................ 1
                ## 正文
                这是正式条款，必须保留。
                """;
        String cleaned = TocNoiseFilter.stripBeforeChunk(md);
        assertFalse(cleaned.contains("........"));
        assertTrue(cleaned.contains("这是正式条款"));
    }

    @Test
    void stripRemovesPlainTextTocThenKeepsBody() {
        String plain = """
                目录
                观发识病\u30001
                视目诊病\u30005
                视口辨病\u300012
                观发识病
                人们常以发乌须黑来评定身体健康程度。的确，如果能经常注意毛发的各种变化，确能察知有无疾病。
                """;
        String cleaned = TocNoiseFilter.stripBeforeChunk(plain);
        assertFalse(cleaned.contains("视口辨病"));
        assertTrue(cleaned.contains("人们常以发乌须黑"));
    }

    @Test
    void stripRemovesEbookNavAndDupTitles() {
        String text = """
                视目诊病

                视目诊病
                视目诊病也有很长的历史。汉代的《内经》就对眼诊作了精辟的论述。
                上一页：一、眼部颜色改变
                当前页：二、眼部形态改变
                下一页：视口辨病
                """;
        String cleaned = TocNoiseFilter.stripBeforeChunk(text);
        assertFalse(cleaned.contains("上一页"));
        assertFalse(cleaned.contains("当前页"));
        assertFalse(cleaned.contains("下一页"));
        assertTrue(cleaned.contains("视目诊病也有很长的历史"));
        // 连续重复的孤立短标题应只保留一行（正文里以「视目诊病也有…」开头的不算重复）
        long standalone = cleaned.lines()
                .map(String::trim)
                .filter(l -> l.equals("视目诊病"))
                .count();
        assertTrue(standalone <= 1);
    }

    @Test
    void sanitizeChunkTextDropsInlineNav() {
        String chunk = "头发性质变化提示甲状腺疾病。\n当前页：观发识病\n下一页：视目诊病";
        String cleaned = TocNoiseFilter.sanitizeChunkText(chunk);
        assertTrue(cleaned.contains("头发性质变化"));
        assertFalse(cleaned.contains("当前页"));
        assertFalse(cleaned.contains("下一页"));
    }

    @Test
    void lowQualityWhenMostlyNav() {
        assertTrue(TocNoiseFilter.isLowQualityChunk("""
                上一页：五、怎样选择配料
                当前页：六、怎样选择茶具
                下一页：七、怎样制作茶饮
                """));
        assertFalse(TocNoiseFilter.isLowQualityChunk(
                "视目诊病也有很长的历史。汉代的《内经》就对眼诊作了精辟的论述，说明眼睛与脏腑相关。"));
    }
}
