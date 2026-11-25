package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点续作用户文本的口径守卫。所属域：runtime 的 domain。
 *
 * <p>为什么单独测：这段文本决定专家「接着做完」还是「只回一句收尾」。
 * 少一段事实（原问句 / 上一轮终答）就会退回到只喂一句选项文案的老毛病，
 * 必须在单测上直接红，而不是等到页面上再次出现「已完成作答」才发现。
 */
class ChoiceContinueTest {

    @Test
    void carriesQuestionAnswerAndSelectionInOrder() {
        String text = ChoiceContinue.text("建议用什么前端技术栈？", "可以走 Vue3 或 React 两条路。", "Vue3 + Vite + TS");
        int question = text.indexOf("【上一轮的问题】");
        int answer = text.indexOf("【上一轮的完整回答】");
        int choice = text.indexOf("【用户刚刚选择】");
        assertTrue(question >= 0, "必须带上原问句：" + text);
        assertTrue(answer > question, "上一轮终答要排在问句之后");
        assertTrue(choice > answer, "所选选项要排在终答之后");
        assertTrue(text.contains("建议用什么前端技术栈？"));
        assertTrue(text.contains("可以走 Vue3 或 React 两条路。"));
        assertTrue(text.contains("Vue3 + Vite + TS"));
    }

    @Test
    void alwaysCarriesTheLockedRequirementToFinishTheJob() {
        String text = ChoiceContinue.text("问句", "上一轮回答", "选项");
        // 锁定要求来自 classpath 资源：必须明确「接着做完 / 给完整方案」，且禁止只回一句收尾
        assertTrue(text.contains("完整"), "必须要求给出完整方案：" + text);
        assertTrue(text.contains("已完成作答"), "必须明确禁止只回一句「已完成作答」");
        assertTrue(text.contains("不要再问") || text.contains("不要把问题再问一遍"),
                "必须明确禁止把问题再问一遍");
    }

    @Test
    void skipsMissingSectionsWithoutLeavingEmptyHeadings() {
        String text = ChoiceContinue.text("", null, "Vue3 + Vite + TS");
        assertFalse(text.contains("【上一轮的问题】"), "没有问句时不能留空标题");
        assertFalse(text.contains("【上一轮的完整回答】"), "没有终答时不能留空标题");
        assertTrue(text.contains("【用户刚刚选择】"));
    }

    @Test
    void continuationAsksForTheAnswerBodyNotTheThinkingChannel() {
        String text = ChoiceContinue.text("问句", "上一轮回答", "Java 17 + Spring Boot 3");
        // 用户手点过的坑：点选后完整方案写进了思考通道，结果框只剩一句「点选后我会给出…」。
        // 续作用户文本必须明确交付内容走正文通道。
        assertTrue(text.contains("回答正文"), "必须要求方案写进回答正文：" + text);
        assertTrue(text.contains("不要只写在思考里") || text.contains("不要写在思考里"),
                "必须明确不要只写在思考里");
        assertTrue(text.contains("开始撰写最终答案"), "必须点名禁止把正文写进思考的自我指令");
    }

    @Test
    void forbidsReplacingWrittenAnswerWithAWrapUpLine() {
        String text = ChoiceContinue.text("问句", "上一轮回答", "选项");
        // 续作轮最容易出的错：把上一轮已经写出的正文换成一句「已完成作答」。
        // 提示词必须明确禁止，前端也不能再拿状态句去顶正文（见 workbenchParticipantFeed 的补位顺序）。
        assertTrue(text.contains("保留"), "必须要求保留上一轮已写出的正文：" + text);
        assertTrue(text.contains("替换") || text.contains("覆盖"), "必须明确禁止用收尾句替换 / 覆盖正文");
    }

    @Test
    void clipsOverlongAnswerButKeepsSelection() {
        String huge = "答".repeat(9000);
        String text = ChoiceContinue.text("问句", huge, "React + Vite");
        assertTrue(text.contains("React + Vite"), "裁剪后仍要带上选项");
        assertTrue(text.length() < huge.length(), "超长终答必须被裁剪");
        assertTrue(text.contains("…"), "裁剪处要有省略号提示后面还有内容");
    }
}
