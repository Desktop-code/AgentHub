package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChoiceCapture;
import com.agenthub.runtime.domain.service.InternalToolNames;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「需要用户拍板」的结构化产出工具。所属域：runtime 的 infra。
 *
 * <p>这一层是首选路径：模型按工具协议给出 question + options，
 * 参数有 schema 约束、结果可直接落库，不必从正文里猜列表。
 * 测试重点：注册即开闸、参数非法不落槽、工具全程静音。
 */
class ChoiceAgentToolTest {

    @Test
    void installMarksEligibleAndRegistersQuietTool() {
        ChoiceCapture capture = new ChoiceCapture();
        Toolkit toolkit = new Toolkit();

        ChoiceAgentTool.install(toolkit, capture);

        assertTrue(capture.isEligible(), "装配工具即表示本轮允许拍板");
        assertNotNull(toolkit.getTool(ChoiceAgentTool.TOOL), "工具必须真的注册进本轮 Toolkit");
        assertTrue(InternalToolNames.isQuiet(ChoiceAgentTool.TOOL), "工具调用不得出现在时间线");
        assertEquals(ChoiceAgentTool.TOOL, InternalToolNames.CHOICE);
    }

    @Test
    void installToleratesNullOrRepeatedCalls() {
        ChoiceCapture capture = new ChoiceCapture();
        // null 入参按「不装配」处理，不抛
        assertNull(ChoiceAgentTool.install(null, capture));
        Toolkit empty = new Toolkit();
        assertSame(empty, ChoiceAgentTool.install(empty, null), "capture 为空时原样返回");
        assertFalse(capture.isEligible(), "没真正装配工具时不应开闸");
        assertNull(empty.getTool(ChoiceAgentTool.TOOL), "capture 为空时不得注册工具");

        Toolkit toolkit = new Toolkit();
        ChoiceAgentTool.install(toolkit, capture);
        // 重复装配不得抛错（P&E 多步可能复用同一 Toolkit）
        ChoiceAgentTool.install(toolkit, capture);
        assertNotNull(toolkit.getTool(ChoiceAgentTool.TOOL));
    }

    @Test
    void validCallWritesToCapture() {
        ChoiceCapture capture = new ChoiceCapture();
        Toolkit toolkit = new Toolkit();
        ChoiceAgentTool.install(toolkit, capture);

        ToolResultBlock result = call(toolkit, validInput());
        AgentChoice choice = capture.choice();

        assertEquals(ToolResultState.SUCCESS, result.getState(), "合法结构应成功");
        assertNotNull(choice, "合法结构必须写进采集槽");
        assertEquals("建议用什么前端技术栈？", choice.question());
        assertEquals(2, choice.options().size());
        assertEquals("vue3", choice.options().get(0).id());
        // 回执只引导一句话收尾，不回灌选项内容（避免正文复述）
        assertTrue(outputText(result).contains("一句话收尾"), outputText(result));
        assertFalse(outputText(result).contains("Vue3 + Vite"), "回执不应回灌选项，正文才不会重复罗列");
    }

    @Test
    void invalidCallKeepsCaptureEmptyAndTellsModelWhy() {
        ChoiceCapture capture = new ChoiceCapture();
        Toolkit toolkit = new Toolkit();
        ChoiceAgentTool.install(toolkit, capture);

        ToolResultBlock tooFew = call(toolkit, input("q",
                List.of(option("a", "A"))));
        assertEquals(ToolResultState.ERROR, tooFew.getState());
        assertTrue(outputText(tooFew).contains("2~6"), outputText(tooFew));
        assertNull(capture.choice(), "非法结构不得写入采集槽");

        ToolResultBlock duplicate = call(toolkit, input("q",
                List.of(option("a", "A"), option("A", "另一个 A"))));
        assertEquals(ToolResultState.ERROR, duplicate.getState(), "id 重复（忽略大小写）应打回");
        assertNull(capture.choice());

        ToolResultBlock blankLabel = call(toolkit, input("q",
                List.of(option("a", ""), option("b", "B"))));
        assertEquals(ToolResultState.ERROR, blankLabel.getState());
        assertNull(capture.choice());

        ToolResultBlock noInput = call(toolkit, null);
        assertEquals(ToolResultState.ERROR, noInput.getState(), "空入参应打回");
        assertNull(capture.choice());
    }

    @Test
    void laterCallOverridesEarlierOne() {
        ChoiceCapture capture = new ChoiceCapture();
        Toolkit toolkit = new Toolkit();
        ChoiceAgentTool.install(toolkit, capture);

        call(toolkit, input("第一次问", List.of(option("a", "A"), option("b", "B"))));
        call(toolkit, input("改了问题", List.of(option("x", "X"), option("y", "Y"), option("z", "Z"))));

        assertNotNull(capture.choice());
        assertEquals("改了问题", capture.choice().question(), "模型改主意时以后一次为准");
        assertEquals(3, capture.choice().options().size());
    }

    @Test
    void numericIdIsCoercedToText() {
        ChoiceCapture capture = new ChoiceCapture();
        Toolkit toolkit = new Toolkit();
        ChoiceAgentTool.install(toolkit, capture);

        List<Map<String, Object>> options = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", 1);
        first.put("label", "方案一");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", 2);
        second.put("label", "方案二");
        options.add(first);
        options.add(second);

        ToolResultBlock result = call(toolkit, input("选哪个", options));
        assertEquals(ToolResultState.SUCCESS, result.getState(), "部分模型会回数字 id，应按文本收下");
        assertEquals("1", capture.choice().options().get(0).id());
    }

    @Test
    void toolFlagsKeepItInvisibleAndSideEffectFree() {
        ChoiceCapture capture = new ChoiceCapture();
        Toolkit toolkit = new Toolkit();
        ChoiceAgentTool.install(toolkit, capture);
        AgentTool tool = toolkit.getTool(ChoiceAgentTool.TOOL);

        assertNotNull(tool);
        assertEquals(Boolean.TRUE, tool.getStrict(), "参数必须严格符合 schema");
        assertTrue(tool.isReadOnly(), "只收进上下文，不产生副作用");
        assertFalse(tool.isReturnDirect(), "结果要回到模型，由模型一句话收尾");
        assertNull(tool.getOutputSchema());
        assertTrue(tool.getDescription().contains("2~6"), tool.getDescription());
    }

    // —— 构造入参 ——

    private static Map<String, Object> validInput() {
        return input("建议用什么前端技术栈？",
                List.of(option("vue3", "Vue3 + Vite + TS"), option("react", "React 19 + TS")));
    }

    private static Map<String, Object> input(String question, List<Map<String, Object>> options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("options", options);
        return body;
    }

    private static Map<String, Object> option(String id, String label) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("id", id);
        one.put("label", label);
        return one;
    }

    private static ToolResultBlock call(Toolkit toolkit, Map<String, Object> input) {
        AgentTool tool = toolkit.getTool(ChoiceAgentTool.TOOL);
        assertNotNull(tool);
        ToolCallParam param = ToolCallParam.builder().input(input).build();
        return tool.callAsync(param).block();
    }

    private static String outputText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        result.getOutput().forEach(block -> {
            if (block instanceof io.agentscope.core.message.TextBlock textBlock) {
                text.append(textBlock.getText()).append(' ');
            }
        });
        return text.toString();
    }
}
