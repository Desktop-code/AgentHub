package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子执行本轮用户句必须隔离为 sub_query；多意图还须砍掉父历史。
 *
 * <p>本类后半段锁入口素材：判路由把 {@code sub_query} 写成一句概述时，
 * 票面 / 抽字 / 转写正文必须仍然跟在问句后面到专家手上。
 */
class ChildSubQueryInputTest {

    /** 一张票面的识图素材，形状与 {@link VisionOcrTexts#materialBlock} 一致 */
    private static final String MATERIAL = VisionOcrTexts.VISION_HEADER + ":\n"
            + "[图1内容]: 旅客运输服务 电子发票（普通发票）\n发票号码：26447000002316009224\n价税合计：￥9.40";

    @Test
    void singleIntentKeepsHistoryPrefix() {
        String parent = "有哪些用户？今日广州天气？";
        String modelUser = "【最近对话】\nuser: 你好\n\n【当前用户】\n" + parent;
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(modelUser, parent, "有哪些用户？", false);
        assertEquals("【最近对话】\nuser: 你好\n\n【当前用户】\n有哪些用户？", hit.modelText());
        assertEquals("有哪些用户？", hit.displaySubQuery());
        assertFalse(hit.fallback());
        assertFalse(hit.modelText().contains("广州天气"));
    }

    @Test
    void multiIntentIsolateDropsParentHistory() {
        String parent = "有哪些用户？今日广州天气？";
        String modelUser = "【最近对话】\nuser: 有哪些用户？今日广州天气？\n\n【当前用户】\n" + parent;
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(modelUser, parent, "有哪些用户？", true);
        assertEquals("有哪些用户？", hit.modelText());
        assertEquals("有哪些用户？", hit.displaySubQuery());
        assertFalse(hit.modelText().contains("广州天气"));
        assertFalse(hit.modelText().contains("最近对话"));
        assertFalse(hit.modelText().contains(parent));
    }

    @Test
    void blankSubQueryFallsBackToParentAndMarks() {
        String parent = "今日广州天气？";
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(parent, parent, "  ", true);
        assertEquals(parent, hit.modelText());
        assertEquals(parent, hit.displaySubQuery());
        assertTrue(hit.fallback());
    }

    @Test
    void neverAppendsParentWhenMarkerMissing() {
        String parent = "有哪些用户？今日广州天气？";
        // 旧逻辑 endsWith 失败时会 modelUser+"\n\n"+sub，导致双写泄漏
        String weird = "前缀不含标记也不以父句结尾";
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(weird, parent, "有哪些用户？", false);
        assertEquals("有哪些用户？", hit.modelText());
        assertFalse(hit.modelText().contains("广州天气"));
        assertFalse(hit.modelText().contains(weird));
    }

    @Test
    void singleIntentSameAsSubQueryKeepsHistory() {
        String parent = "今日广州天气？";
        String modelUser = "【当前用户】\n" + parent;
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(modelUser, parent, parent, false);
        assertEquals(modelUser, hit.modelText());
        assertFalse(hit.fallback());
    }

    /**
     * 入口首轮：sub_query 是判路由写的一句概述，素材必须跟着到专家。
     *
     * <p>这是本刀要修的主症状——修前专家只看到「用户仅上传了一张发票…请先确认诉求」，
     * 于是反过来向用户再要一遍票面字段。
     */
    @Test
    void summarySubQueryKeepsMaterialForSingleIntent() {
        String userText = VisionOcrTexts.composeQuery("[图1内容]: 旅客运输服务 电子发票", "");
        String modelUser = "【当前用户】\n" + userText;
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(
                modelUser, userText, "用户仅上传了一张发票，未说明诉求，请先确认", false, MATERIAL);

        // 问句被换成概述，父历史（这里就是原文）不再出现
        assertTrue(hit.modelText().startsWith("【当前用户】\n用户仅上传了一张发票，未说明诉求，请先确认"),
                hit.modelText());
        // 素材仍在，且票面字段可读
        assertTrue(hit.modelText().contains("26447000002316009224"), hit.modelText());
        assertTrue(hit.modelText().contains("￥9.40"), hit.modelText());
        // 展示用的仍是子问，不带素材（span / 思考只显示本轮问句）
        assertEquals("用户仅上传了一张发票，未说明诉求，请先确认", hit.displaySubQuery());
    }

    /** 多意图隔离砍的是父历史，不是本轮素材。 */
    @Test
    void isolateKeepsMaterialButDropsHistory() {
        String userText = "看看这张票";
        String modelUser = "【最近对话】\nuser: 上一轮的问题\n\n【当前用户】\n" + userText;
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(
                modelUser, userText, "整理报销材料", true, MATERIAL);

        assertTrue(hit.modelText().startsWith("整理报销材料"), hit.modelText());
        assertTrue(hit.modelText().contains("26447000002316009224"), hit.modelText());
        assertFalse(hit.modelText().contains("上一轮的问题"), hit.modelText());
        assertEquals("整理报销材料", hit.displaySubQuery());
    }

    /**
     * subQuery 恒等于原文的那几条路径（点名方案 / 关键字 / 固定模板 / 手动换线）：
     * 素材本来就在问句里，不能再拼第二份。
     */
    @Test
    void subQueryEqualToParentDoesNotDuplicateMaterial() {
        // 原文本身就带着素材：这几条路径的 subQuery 直接取 userText
        String original = "整理这张票\n\n" + MATERIAL;
        String modelUser = "【当前用户】\n" + original;
        ChildSubQueryInput.Result hit = ChildSubQueryInput.resolve(modelUser, original, original, false, MATERIAL);

        // 历史前缀（这里是【当前用户】标记）保留，本轮原文原样不重复
        assertEquals(modelUser, hit.modelText());
        // 票面只出现一次
        assertEquals(1, countOf(hit.modelText(), "26447000002316009224"), hit.modelText());
    }

    /** 没有素材时与旧行为逐字一致。 */
    @Test
    void noMaterialKeepsLegacyBehaviour() {
        String parent = "有哪些用户？今日广州天气？";
        String modelUser = "【最近对话】\nuser: 你好\n\n【当前用户】\n" + parent;
        ChildSubQueryInput.Result withNull = ChildSubQueryInput.resolve(modelUser, parent, "有哪些用户？", false, null);
        ChildSubQueryInput.Result legacy = ChildSubQueryInput.resolve(modelUser, parent, "有哪些用户？", false);
        assertEquals(legacy.modelText(), withNull.modelText());
        assertEquals("【最近对话】\nuser: 你好\n\n【当前用户】\n有哪些用户？", withNull.modelText());
    }

    /** 匹配文本 = 子问 + 素材，供技能触发与记忆召回使用。 */
    @Test
    void matchTextCarriesMaterialForSkillAndRecall() {
        String match = ChildSubQueryInput.matchText("确认用户诉求", MATERIAL);
        assertTrue(match.startsWith("确认用户诉求"), match);
        assertTrue(match.contains("26447000002316009224"), match);
        // 没有素材时就是子问本身
        assertEquals("确认用户诉求", ChildSubQueryInput.matchText("确认用户诉求", null));
        assertEquals(MATERIAL, ChildSubQueryInput.matchText(null, MATERIAL));
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
