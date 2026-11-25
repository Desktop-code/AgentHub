package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项相关平台锁定文案的口径守卫。所属域：runtime 的 domain。
 *
 * <p>为什么值得单独测：文案决定模型走哪条路。
 * 一旦有人把系统提示改回「输出围栏 JSON / 正文列表」，就会悄悄退回「靠猜正文」的老路，
 * 而这套规则已经被实测证明不可靠——必须让回退在单测上直接红。
 */
class ChoicePromptTextTest {

    @Test
    void systemNotePointsModelToTheTool() {
        String note = ChoiceNote.text();
        assertTrue(note.contains(InternalToolNames.CHOICE), "系统提示必须点名工具：" + note);
        assertTrue(note.contains("2~6"), "必须给出选项数量边界");
        assertTrue(note.contains("一句话"), "收尾必须限定为一句，避免正文重复罗列");
        assertFalse(note.contains("```" + WorkbenchChoiceJson.FENCE_LANG),
                "系统提示不得再教模型输出旧围栏块（会退回猜正文那条路）");
    }

    @Test
    void channelNoteLocksDeliverablesToTheAnswerBody() {
        String note = AnswerChannelNote.text();
        // 用户手点过的坑：点完选项，方案 / 代码 / 表格整份写进了思考通道，正文只剩一句收尾。
        // 平台锁定文案必须明确「可交付内容走正文」，并点名禁止把方案写成思考草稿。
        assertTrue(note.contains("正文"), "必须明确交付内容是回答正文：" + note);
        assertTrue(note.contains("思考"), "必须点明思考通道只放推理");
        assertTrue(note.contains("开始撰写最终答案"), "必须点名禁止「开始撰写最终答案」这类自我指令");
        assertTrue(note.contains("点选后我会给出"), "必须点名禁止「点选后我会给出…」式收尾顶替方案");
        assertFalse(note.contains("```"), "口径文案不得带头围栏，避免被模型当成输出格式示例");
    }

    @Test
    void continuationTurnMustKeepWrittenAnswer() {
        // 用户手点过的坑：点完选项，续作把整段方案换成一句「已完成作答」。
        // 平台锁定文案必须明确：没调工具的这一轮，正文本身就是交付物，不能被收尾句替换。
        String note = ChoiceNote.text();
        assertTrue(note.contains("已完成作答"), "必须点名禁止用「已完成作答」替换正文：" + note);
        assertTrue(note.contains("替换"), "必须明确禁止替换已经写出的正文");
    }

    @Test
    void choiceNoteBansModelOwnedOtherOption() {
        // 平台会在模型候选之后固定追加一行「其它，我自己说」；模型再自带同义项就会出现两行，
        // 其中一行还会被当成答复提交。锁定文案必须点名这条纪律。
        String note = ChoiceNote.text();
        assertTrue(note.contains("其它"), "必须点名平台会追加其它 / 自填行：" + note);
        assertTrue(note.contains("追加"), "必须说明这行由平台追加，不是模型给的候选");
    }

    @Test
    void structurePromptAsksForTheSameShapeAndAllowsRefusal() {
        String prompt = ChoiceStructurePrompt.text();
        assertTrue(prompt.contains("\"question\""), "必须要求 question 字段");
        assertTrue(prompt.contains("\"options\""), "必须要求 options 字段");
        assertTrue(prompt.contains("\"id\"") && prompt.contains("\"label\""), "每项必须有 id 与 label");
        assertTrue(prompt.contains("2~6"), "必须给出选项数量边界");
        // 「不是请用户选择」时要有确定的否答形态，整理器据此判成「不出 chips」
        assertTrue(prompt.contains("\"options\":[]"), "必须允许空表作为「否」的回答");
    }
}
