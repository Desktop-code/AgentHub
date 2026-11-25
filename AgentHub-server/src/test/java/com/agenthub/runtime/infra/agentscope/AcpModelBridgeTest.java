package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACL Adapter → AgentScope Model 的桥接单测。所属域：runtime 的 infra。
 * 为什么需要它：AgentScope 2.0.4 没有 Anthropic 模型实现，试跑要靠本桥接把 AnthropicAdapter 接进引擎；
 * 桥接的翻帧规则一旦错，表现是「前端有字但没有工具卡片」或「引擎拿半截 JSON 当参数」，很难从页面看出根因。
 * 覆盖：出站消息映射（system/tool 拆分、工具 schema）、入站事件翻帧（正文/思考/工具/usage/done/error）。
 * 约束：Adapter 用内存桩，零外网、零真实 Key。
 */
class AcpModelBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 出站：system 与 user 直传，工具 schema 的 Map 参数被序列化成统一层要的 JSON 字符串。
     */
    @Test
    @DisplayName("出站把引擎消息与工具 schema 翻成统一请求")
    void mapsOutboundMessagesAndTools() {
        CapturingAdapter adapter = new CapturingAdapter(Flux.empty());
        AcpModelBridge bridge = new AcpModelBridge(adapter, "claude-test", JSON);

        List<Msg> messages = List.of(
                new SystemMessage("你是助手"),
                new UserMessage("你好"));
        List<ToolSchema> tools = List.of(ToolSchema.builder()
                .name("weather_query")
                .description("查天气")
                .parameters(Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))
                .build());
        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.3)
                .maxTokens(256)
                .build();

        bridge.stream(messages, tools, options).collectList().block(Duration.ofSeconds(5));

        UnifiedChatRequest sent = adapter.captured;
        assertNotNull(sent, "应把请求交给 Adapter");
        assertEquals(2, sent.getMessages().size());
        assertEquals("system", sent.getMessages().get(0).getRole());
        assertEquals("你是助手", sent.getMessages().get(0).getContent());
        assertEquals("user", sent.getMessages().get(1).getRole());
        assertEquals(0.3, sent.getTemperature());
        assertEquals(256, sent.getMaxTokens());
        assertTrue(sent.isStream(), "桥接只服务流式引擎");

        assertEquals(1, sent.getTools().size());
        assertEquals("weather_query", sent.getTools().get(0).getName());
        // 统一层要字符串，Adapter 自行解析
        assertTrue(sent.getTools().get(0).getParametersJsonSchema().startsWith("{"));
    }

    /**
     * 出站：TOOL 消息里的多个 tool_result 必须拆成多条统一 tool 消息，否则会丢结果或错配 id。
     */
    @Test
    @DisplayName("出站把一条 TOOL 消息拆成多条 tool 结果")
    void splitsToolResultMessage() {
        CapturingAdapter adapter = new CapturingAdapter(Flux.empty());
        AcpModelBridge bridge = new AcpModelBridge(adapter, "m", JSON);

        Msg toolMsg = ToolResultMessage.builder()
                .result(new ToolResultBlock("call_1", "tool_a", List.of(TextBlock.builder().text("结果一").build())))
                .result(new ToolResultBlock("call_2", "tool_b", List.of(TextBlock.builder().text("结果二").build())))
                .build();

        bridge.stream(List.of(toolMsg), null, null).collectList().block(Duration.ofSeconds(5));

        List<com.agenthub.runtime.model.acl.UnifiedMessage> sent = adapter.captured.getMessages();
        assertEquals(2, sent.size(), "两个 tool_result 应拆成两条统一消息");
        assertEquals("tool", sent.get(0).getRole());
        assertEquals("call_1", sent.get(0).getToolCallId());
        assertEquals("结果一", sent.get(0).getContent());
        assertEquals("call_2", sent.get(1).getToolCallId());
    }

    /**
     * 出站：助手历史里的 tool_use 必须带回参数，否则多轮工具对话丢上下文。
     */
    @Test
    @DisplayName("出站把助手历史工具调用带回统一消息")
    void mapsAssistantToolCalls() {
        CapturingAdapter adapter = new CapturingAdapter(Flux.empty());
        AcpModelBridge bridge = new AcpModelBridge(adapter, "m", JSON);

        Msg assistant = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder()
                        .id("call_x")
                        .name("weather_query")
                        .input(Map.of("city", "北京"))
                        .content("{\"city\":\"北京\"}")
                        .build())
                .build();

        bridge.stream(List.of(assistant), null, null).collectList().block(Duration.ofSeconds(5));

        var sent = adapter.captured.getMessages().get(0);
        assertEquals("assistant", sent.getRole());
        assertEquals(1, sent.getToolCalls().size());
        assertEquals("call_x", sent.getToolCalls().get(0).getId());
        assertEquals("{\"city\":\"北京\"}", sent.getToolCalls().get(0).getArguments());
    }

    /**
     * 入站：正文与思考各自翻帧，顺序保持不变（引擎按到达顺序累积）。
     */
    @Test
    @DisplayName("入站把思考与正文翻成各自的内容块")
    void mapsThinkingAndTextFrames() {
        Flux<UnifiedStreamEvent> events = Flux.just(
                UnifiedStreamEvent.thinkingDelta("先想"),
                UnifiedStreamEvent.messageDelta("答"),
                UnifiedStreamEvent.messageDelta("案"),
                UnifiedStreamEvent.done(UnifiedFinishReason.STOP, null));
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(events), "m", JSON);

        List<ChatResponse> frames = bridge.stream(List.of(new UserMessage("hi")), null, null)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        // 思考 + 两段正文 + done 帧
        assertEquals(4, frames.size());
        assertTrue(frames.get(0).getContent().get(0) instanceof ThinkingBlock);
        assertEquals("先想", ((ThinkingBlock) frames.get(0).getContent().get(0)).getThinking());
        assertEquals("答", ((TextBlock) frames.get(1).getContent().get(0)).getText());
        assertEquals("案", ((TextBlock) frames.get(2).getContent().get(0)).getText());
        assertEquals("stop", frames.get(3).getFinishReason());
    }

    /**
     * 入站关键点：工具调用只在 completed 上产出 ToolUseBlock，delta 碎片不投递。
     * 若把半截 JSON 投给引擎，它会当成参数终值，工具直接执行失败。
     */
    @Test
    @DisplayName("入站只把 tool_call.completed 翻成工具块，碎片不投递")
    void mapsToolCallOnlyOnCompleted() {
        Flux<UnifiedStreamEvent> events = Flux.just(
                UnifiedStreamEvent.builder()
                        .type(com.agenthub.runtime.model.acl.UnifiedStreamEventType.TOOL_CALL_DELTA)
                        .toolCallDelta(UnifiedToolCall.builder().id("c1").name("w").arguments("{\"ci").build())
                        .build(),
                UnifiedStreamEvent.builder()
                        .type(com.agenthub.runtime.model.acl.UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                        .toolCallDelta(UnifiedToolCall.builder()
                                .id("c1").name("w").arguments("{\"city\":\"北京\"}").build())
                        .build(),
                UnifiedStreamEvent.done(UnifiedFinishReason.TOOL_CALLS, null));
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(events), "m", JSON);

        List<ChatResponse> frames = bridge.stream(List.of(new UserMessage("hi")), null, null)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        List<ToolUseBlock> toolBlocks = frames.stream()
                .flatMap(f -> f.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
        assertEquals(1, toolBlocks.size(), "碎片不应产生额外工具块，实际 " + toolBlocks.size());
        ToolUseBlock block = toolBlocks.get(0);
        assertEquals("c1", block.getId());
        assertEquals("w", block.getName());
        // 参数必须是解析好的 Map，而不是半截字符串
        assertEquals("北京", block.getInput().get("city"));
        assertEquals("{\"city\":\"北京\"}", block.getContent(), "原始参数应保留在 content 上");
    }

    /**
     * 入站：usage 事件要翻成引擎用量，否则计费与观测拿不到 token。
     */
    @Test
    @DisplayName("入站把 usage 翻成引擎用量")
    void mapsUsageFrame() {
        Flux<UnifiedStreamEvent> events = Flux.just(
                UnifiedStreamEvent.builder()
                        .type(com.agenthub.runtime.model.acl.UnifiedStreamEventType.USAGE)
                        .usage(UnifiedUsage.builder().prompt(11).completion(5).total(16).build())
                        .build(),
                UnifiedStreamEvent.done(UnifiedFinishReason.STOP, null));
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(events), "m", JSON);

        List<ChatResponse> frames = bridge.stream(List.of(new UserMessage("hi")), null, null)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        ChatResponse usageFrame = frames.stream().filter(f -> f.getUsage() != null).findFirst().orElse(null);
        assertNotNull(usageFrame, "应有一帧带用量");
        assertEquals(11, usageFrame.getUsage().getInputTokens());
        assertEquals(5, usageFrame.getUsage().getOutputTokens());
    }

    /**
     * 入站：error 事件必须让整条流失败，并包成引擎认识的 ModelException；文案不得被改写。
     */
    @Test
    @DisplayName("入站 error 转成引擎异常且不改写脱敏文案")
    void mapsErrorToModelException() {
        Flux<UnifiedStreamEvent> events = Flux.just(
                UnifiedStreamEvent.error("模型服务返回 HTTP 401", UnifiedFinishReason.ERROR),
                UnifiedStreamEvent.done(UnifiedFinishReason.ERROR, null));
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(events), "m", JSON);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> bridge.stream(List.of(new UserMessage("hi")), null, null).collectList().block());

        assertTrue(error instanceof ModelException, "应包成引擎异常，实际 " + error.getClass().getSimpleName());
        assertEquals("模型服务返回 HTTP 401", error.getMessage(), "脱敏文案应原样保留");
    }

    /**
     * 入站：上游只给 done（截断兜底）时，仍要有一帧收尾，否则引擎会以为调用没结束。
     */
    @Test
    @DisplayName("只有 done 时也产出收尾帧")
    void emitsFrameForLoneDone() {
        Flux<UnifiedStreamEvent> events = Flux.just(
                UnifiedStreamEvent.done(UnifiedFinishReason.LENGTH, null));
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(events), "m", JSON);

        List<ChatResponse> frames = bridge.stream(List.of(new UserMessage("hi")), null, null)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertEquals(1, frames.size());
        assertEquals("length", frames.get(0).getFinishReason());
        assertTrue(frames.get(0).getContent().isEmpty());
    }

    /**
     * 引擎能力查询：统一层不声明 structured output，返回 false 让引擎走兼容路径。
     */
    @Test
    @DisplayName("能力查询走保守默认值")
    void reportsConservativeCapabilities() {
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(Flux.empty()), "claude-test", JSON);

        assertEquals("claude-test", bridge.getModelName());
        assertFalse(bridge.supportsNativeStructuredOutput());
        assertFalse(bridge.supportsNativeStructuredOutputWithTools());
        assertEquals(0, bridge.getContextWindowSize());
    }

    /**
     * 空事件流不应产生帧，也不应抛错。
     */
    @Test
    @DisplayName("空事件流产出空帧列表")
    void emptyStreamProducesNoFrames() {
        AcpModelBridge bridge = new AcpModelBridge(new CapturingAdapter(Flux.empty()), "m", JSON);

        List<ChatResponse> frames = bridge.stream(List.of(new UserMessage("hi")), null, null)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertTrue(frames.isEmpty());
    }

    /**
     * 不采样用途（EMBEDDING / RERANK / ASR / TTS / GUARDRAIL）出站不得带 temperature：
     * 引擎即使给了 0，也必须留 null，由 Adapter 省略字段，避免厂商把 0 当成显式采样。
     */
    @Test
    @DisplayName("不允许采样时不把温度带出站")
    void dropsTemperatureWhenNotSampling() {
        CapturingAdapter adapter = new CapturingAdapter(Flux.empty());
        AcpModelBridge bridge = new AcpModelBridge(adapter, "embed-model", JSON, false);

        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.0)
                .maxTokens(64)
                .build();
        bridge.stream(List.of(new UserMessage("hi")), null, options).collectList().block(Duration.ofSeconds(5));

        UnifiedChatRequest sent = adapter.captured;
        assertNotNull(sent);
        assertNull(sent.getTemperature(), "不采样用途不得带温度");
        // 其它生成参数不受影响
        assertEquals(64, sent.getMaxTokens());
    }

    /**
     * 对话类用途仍应把引擎温度原样带出站。
     */
    @Test
    @DisplayName("允许采样时温度正常带出站")
    void keepsTemperatureWhenSampling() {
        CapturingAdapter adapter = new CapturingAdapter(Flux.empty());
        AcpModelBridge bridge = new AcpModelBridge(adapter, "chat-model", JSON, true);

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        bridge.stream(List.of(new UserMessage("hi")), null, options).collectList().block(Duration.ofSeconds(5));

        assertEquals(0.7, adapter.captured.getTemperature());
    }

    /**
     * 只记录请求、按预置事件流应答的 Adapter 桩。不发任何网络请求。
     */
    private static final class CapturingAdapter implements ModelProviderAdapter {

        private final Flux<UnifiedStreamEvent> events;
        private UnifiedChatRequest captured;

        /**
         * @param events 预置的流式事件
         */
        CapturingAdapter(Flux<UnifiedStreamEvent> events) {
            this.events = events;
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public UnifiedChatResponse chat(UnifiedChatRequest request) {
            throw new UnsupportedOperationException("桥接只走流式");
        }

        @Override
        public Flux<UnifiedStreamEvent> chatStream(UnifiedChatRequest request) {
            this.captured = request;
            return events;
        }
    }
}
