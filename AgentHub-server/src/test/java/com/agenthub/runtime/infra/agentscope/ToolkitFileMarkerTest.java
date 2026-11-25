package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.TrialProgress;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物标记在 Toolkit 包装链路里的拦截行为。所属域：runtime。
 *
 * <p>这是「生成即下载」的核心闸门，重点验证三件事：
 * 标记被提取成产物回调、标记从回灌文本里被物理剔除、无标记的工具结果不受影响。
 * 不连库、不起 Spring 容器，也不触网。
 */
class ToolkitFileMarkerTest {

    /**
     * 包装后的工具必须把标记里的文件名与体积交给 sink，且不再把标记回给模型。
     * 这是用户在聊天区看到下载卡片的唯一来源。
     */
    @Test
    void interceptsMarkerAndPublishesFile() {
        List<GeneratedFile> received = new ArrayList<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new MarkerTool("workspaceWriteFile"));

        ToolkitFileMarker.wrap(toolkit, received::add);
        ToolResultBlock result = call(toolkit, "workspaceWriteFile");

        // 产物必须被提取出来，否则前端收不到 file_generated
        assertEquals(1, received.size(), "应提取出 1 个产物");
        assertEquals("starfield.html", received.get(0).fileName());
        assertEquals(9240L, received.get(0).size());

        // 标记必须被物理剔除，否则模型会在下一轮复述这段机器字符
        String text = textOf(result);
        assertFalse(text.contains(GeneratedFileMarker.OPEN), "标记不应残留在回灌文本里：" + text);
        assertFalse(text.contains(GeneratedFileMarker.CLOSE), "闭合标记不应残留：" + text);
        assertTrue(text.contains("/workspace/starfield.html"), "人话部分必须保留：" + text);
    }

    /**
     * 没有标记的普通工具结果必须原样返回，不能因为包装而被改写。
     */
    @Test
    void passesThroughPlainResult() {
        List<GeneratedFile> received = new ArrayList<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new PlainTool("queryWeather"));

        ToolkitFileMarker.wrap(toolkit, received::add);

        assertTrue(received.isEmpty(), "普通工具不应产生产物");
        assertEquals("今天晴，26 度", textOf(call(toolkit, "queryWeather")));
    }

    /**
     * 包装不得改变工具名：模型是按名字调用的，改名等于工具消失。
     */
    @Test
    void keepsToolName() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new MarkerTool("workspaceWriteFile"));
        ToolkitFileMarker.wrap(toolkit, file -> { });

        assertTrue(toolkit.getToolNames().contains("workspaceWriteFile"));
    }

    /**
     * 出口为空时仍要剔除标记：模型上下文不该被这段字符污染，
     * 这条保证「没配前端推送也不会污染对话」。
     */
    @Test
    void stripsMarkerEvenWithoutSink() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new MarkerTool("workspaceWriteFile"));
        ToolkitFileMarker.wrap(toolkit, null);

        assertFalse(textOf(call(toolkit, "workspaceWriteFile")).contains(GeneratedFileMarker.OPEN));
    }

    /**
     * 直接执行一次工具调用，返回结果块。
     *
     * @param toolkit 已包装的工具集
     * @param name    工具名
     * @return 工具结果
     */
    private static ToolResultBlock call(Toolkit toolkit, String name) {
        AgentTool tool = toolkit.getTool(name);
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name(name).input(Map.of()).build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(use)
                .input(Map.of())
                .runtimeContext(RuntimeContext.builder().sessionId("trial-1").build())
                .build();
        return tool.callAsync(param).block();
    }

    /**
     * @param result 工具结果
     * @return 拼接后的文本正文
     */
    private static String textOf(ToolResultBlock result) {
        StringBuilder text = new StringBuilder();
        for (var block : result.getOutput()) {
            if (block instanceof TextBlock t) {
                text.append(t.getText());
            }
        }
        return text.toString();
    }

    /** 模拟 workspaceWriteFile：返回带产物标记的成功文案 */
    private static final class MarkerTool implements AgentTool {
        private final String name;

        private MarkerTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "写文件";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String text = "文件已成功写入。你可以通过路径 /workspace/starfield.html 在后续任务中引用它。"
                    + "（覆盖写入 9240 字节，当前共 9240 字节）\n"
                    + GeneratedFileMarker.mark("starfield.html", 9240);
            return reactor.core.publisher.Mono.just(
                    new ToolResultBlock("call-1", name, List.of(TextBlock.builder().text(text).build())));
        }
    }

    /** 模拟普通工具：结果里没有标记 */
    private static final class PlainTool implements AgentTool {
        private final String name;

        private PlainTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "查天气";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return reactor.core.publisher.Mono.just(
                    new ToolResultBlock("call-1", name,
                            List.of(TextBlock.builder().text("今天晴，26 度").build())));
        }
    }
}
