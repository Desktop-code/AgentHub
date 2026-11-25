package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedToolDef;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic Messages 协议 Adapter 单测。所属域：runtime（ACL 实现）。
 * 覆盖：content block 结构化事件、thinking block → thinking.delta、tool_use 的
 * input_json_delta 碎片拼装、缺 message_stop 的截断兜底、上游错误脱敏、
 * 非流式 content blocks 映射，以及出站请求的关键差异（system 顶层、x-api-key、input_schema、tool_result）。
 * 约束：全部走本地假上游，零真实外网、零真实 Key。
 */
class AnthropicAdapterTest extends AclAdapterTestSupport {

    /** Messages 接口路径 */
    private static final String MESSAGES_PATH = "/v1/messages";

    /**
     * 正常文本流：content_block_delta 的 text_delta 拼成正文，message_delta 给 stop_reason 与 output_tokens。
     */
    @Test
    @DisplayName("text_delta 拼出正文，message_delta 收尾且 usage 只发一次")
    void streamsTextAndUsage() {
        respondStreaming(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                        + "\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"，世界\"}}"),
                dataFrame("{\"type\":\"content_block_stop\",\"index\":0}"),
                dataFrame("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"output_tokens\":9}}"),
                dataFrame("{\"type\":\"message_stop\"}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals("你好，世界", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        assertEquals(1, events.stream().filter(e -> e.getType() == UnifiedStreamEventType.USAGE).count(),
                "usage 事件只应发一次");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.STOP, done.getFinishReason());
        // message_start 给 input，message_delta 给 output，两次都要吸收
        assertNotNull(done.getUsage());
        assertEquals(12, done.getUsage().getPrompt());
        assertEquals(9, done.getUsage().getCompletion());
        assertEquals(21, done.getUsage().getTotal());
    }

    /**
     * 思考块：Anthropic 用独立 content block（type=thinking）+ thinking_delta，
     * 与 OpenAI 的 reasoning_content 字段完全不同；必须映射为 thinking.delta 且不混入正文。
     */
    @Test
    @DisplayName("thinking block 映射为 thinking.delta，不混入正文")
    void mapsThinkingBlockSeparately() {
        respondStreaming(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先分析\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"再回答\"}}"),
                // 签名帧不含文本，必须忽略
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc\"}}"),
                dataFrame("{\"type\":\"content_block_stop\",\"index\":0}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"答案\"}}"),
                dataFrame("{\"type\":\"content_block_stop\",\"index\":1}"),
                dataFrame("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}"),
                dataFrame("{\"type\":\"message_stop\"}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals("先分析再回答", concatContent(events, UnifiedStreamEventType.THINKING_DELTA),
                "思考必须是独立事件，不能进正文");
        assertEquals("答案", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        assertSingleDoneAtEnd(events);
    }

    /**
     * 核心差异化用例：tool_use 的参数是 input_json_delta.partial_json 文本碎片，
     * 按 content block index 归组拼接，拼完整后只发一次 completed。
     */
    @Test
    @DisplayName("tool_use 的 input_json_delta 碎片拼成完整参数后发 completed")
    void assemblesToolUsePartialJson() {
        respondStreaming(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"weather_query\",\"input\":{}}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\"\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\":\\\"北京\\\"}\"}}"),
                dataFrame("{\"type\":\"content_block_stop\",\"index\":0}"),
                dataFrame("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":7}}"),
                dataFrame("{\"type\":\"message_stop\"}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        List<UnifiedToolCall> completed = events.stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                .map(UnifiedStreamEvent::getToolCallDelta)
                .toList();
        assertEquals(1, completed.size(), "tool_use 参数碎片应合并为一次 completed，实际 " + completed.size());
        assertEquals("toolu_01", completed.get(0).getId());
        assertEquals("weather_query", completed.get(0).getName());
        assertEquals("{\"city\":\"北京\"}", completed.get(0).getArguments());
        assertTrue(isValidJsonObject(completed.get(0).getArguments()), "completed 参数必须是合法 JSON");

        // 定稿 §6 要求「参数增量事件可观测」，不能只有最终一把 JSON
        assertTrue(events.stream().anyMatch(e -> e.getType() == UnifiedStreamEventType.TOOL_CALL_DELTA
                        && e.getToolCallDelta() != null && "toolu_01".equals(e.getToolCallDelta().getId())),
                "参数碎片应同时产生 tool_call.delta 供前端观测进度");

        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.TOOL_CALLS, done.getFinishReason());
    }

    /**
     * 两个 tool_use 块交错：必须按 block index 各自归组，不能串到一个调用里。
     */
    @Test
    @DisplayName("多个 tool_use 块按 index 各自归组")
    void keepsParallelToolUseSeparated() {
        respondStreaming(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_a\",\"name\":\"tool_a\",\"input\":{}}}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_b\",\"name\":\"tool_b\",\"input\":{}}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"y\\\":2}\"}}"),
                dataFrame("{\"type\":\"content_block_stop\",\"index\":0}"),
                dataFrame("{\"type\":\"content_block_stop\",\"index\":1}"),
                dataFrame("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}"),
                dataFrame("{\"type\":\"message_stop\"}")));

        List<UnifiedToolCall> calls = collect(adapter()).stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                .map(UnifiedStreamEvent::getToolCallDelta)
                .toList();

        assertEquals(2, calls.size());
        assertEquals("toolu_a", calls.get(0).getId());
        assertEquals("{\"x\":1}", calls.get(0).getArguments());
        assertEquals("toolu_b", calls.get(1).getId());
        assertEquals("{\"y\":2}", calls.get(1).getArguments());
    }

    /**
     * 关键差异化场景：Anthropic 没有 [DONE]，正常靠 message_stop 收尾。
     * 上游只推正文就断开（既无 message_delta 也无 message_stop）时，仍必须补 done，
     * 且原因标 LENGTH（数据可用但不完整）而不是伪装成正常 STOP。
     */
    @Test
    @DisplayName("缺 message_stop 的截断流补 done 并标 LENGTH")
    void truncatedStreamEmitsDoneWithLength() {
        respondStreaming(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"),
                dataFrame("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"半截\"}}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals("半截", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.LENGTH, done.getFinishReason(),
                "截断不给 ERROR：数据可用但不完整，LENGTH 提示更准确");
    }

    /**
     * 上游 5xx：先 error 再 done，文案不含响应体原文。
     */
    @Test
    @DisplayName("上游 5xx 时先发脱敏 error 再补 done")
    void upstreamErrorEmitsRedactedErrorThenDone() {
        respondError(MESSAGES_PATH, 529, "{\"type\":\"error\",\"error\":{\"message\":\"sk-leak-should-not-appear\"}}");

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals(UnifiedStreamEventType.ERROR, events.get(0).getType());
        assertFalse(events.get(0).getContent().contains("sk-leak-should-not-appear"),
                "错误摘要不得包含响应体原文");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.ERROR, done.getFinishReason());
    }

    /**
     * 流内 error 帧：Anthropic 用 {"type":"error"} 在流中途报错（如 overloaded），
     * 必须转成统一 error 事件，且 done 只能有一帧。
     */
    @Test
    @DisplayName("流内 error 帧转为统一 error 且 done 不重复")
    void inStreamErrorFrameMapped() {
        respondStreaming(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"),
                dataFrame("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"服务繁忙\"}}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertTrue(events.stream().anyMatch(e -> e.getType() == UnifiedStreamEventType.ERROR),
                "流内 error 帧应转为 error 事件");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.ERROR, done.getFinishReason());
    }

    /**
     * 读空闲超时：Anthropic 心跳缺失时上游会卡住，必须有 error + done(TIMEOUT)。
     */
    @Test
    @DisplayName("上游卡住不推数据时超时收尾，done 标 TIMEOUT")
    void idleUpstreamEndsWithTimeout() {
        respondStalling(MESSAGES_PATH, String.join("",
                dataFrame("{\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}"),
                dataFrame("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"开头\"}}")),
                1500);

        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .providerName("anthropic")
                .baseUrl(baseUrl)
                .apiKey(FAKE_KEY)
                .modelName("test-model")
                .streamIdleTimeout(Duration.ofMillis(300))
                .build();
        List<UnifiedStreamEvent> events = new AnthropicAdapter(config, client(), JSON)
                .chatStream(minimalRequest()).collectList().block(Duration.ofSeconds(10));

        assertNotNull(events);
        assertEquals("开头", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA),
                "超时前到达的正文必须保留");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.TIMEOUT, done.getFinishReason());
    }

    /**
     * 出站请求差异（一）：system 必须是顶层字段而不是 messages 里的角色；
     * 鉴权头是 x-api-key + anthropic-version，不是 Bearer；max_tokens 必填。
     */
    @Test
    @DisplayName("出站请求 system 提到顶层、用 x-api-key 与 input_schema")
    void buildsAnthropicRequestShape() {
        respondJson(MESSAGES_PATH, "{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"stop_reason\":\"end_turn\"}");

        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .messages(List.of(
                        UnifiedMessage.system("前缀提示"),
                        UnifiedMessage.system("Agent 提示"),
                        UnifiedMessage.user("hi")))
                .tools(List.of(UnifiedToolDef.builder()
                        .name("weather_query")
                        .description("查天气")
                        .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                        .build()))
                .temperature(0.4)
                .build();
        adapter().chat(request);

        assertNotNull(receivedHeaders.get());
        assertEquals(FAKE_KEY, receivedHeaders.get().getFirst("x-api-key"),
                "Anthropic 用 x-api-key 头，不是 Authorization");
        assertNull(receivedHeaders.get().getFirst("Authorization"), "不应发 Bearer 头");
        assertEquals("2023-06-01", receivedHeaders.get().getFirst("anthropic-version"),
                "anthropic-version 是必填头");

        JsonNode sent = readReceivedBody();
        // 两条 system 必须合并成一个顶层字符串，且 messages 里不能再有 system
        assertEquals("前缀提示\n\nAgent 提示", sent.get("system").asText());
        JsonNode messages = sent.get("messages");
        assertEquals(1, messages.size(), "system 不能留在 messages 里");
        assertEquals("user", messages.get(0).get("role").asText());
        // max_tokens 必填，业务没给时要补默认值
        assertTrue(sent.get("max_tokens").asInt() > 0, "max_tokens 是 Anthropic 必填项");
        assertEquals(0.4, sent.get("temperature").asDouble());
        // tools 是顶层数组，参数 schema 字段名是 input_schema（不是 parameters）
        JsonNode tool = sent.get("tools").get(0);
        assertEquals("weather_query", tool.get("name").asText());
        assertTrue(tool.get("input_schema").isObject(), "Anthropic 用 input_schema");
        assertNull(tool.get("function"), "Anthropic 不包 function 一层");
    }

    /**
     * 出站请求差异（二）：工具结果必须是 user 消息里的 tool_result block，
     * assistant 的历史工具调用必须是 tool_use block。这两条不转会被上游 400。
     */
    @Test
    @DisplayName("tool 结果转 tool_result block，历史 tool_calls 转 tool_use block")
    void mapsToolResultAndToolUseBlocks() {
        respondJson(MESSAGES_PATH, "{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"stop_reason\":\"end_turn\"}");

        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .messages(List.of(
                        UnifiedMessage.user("北京天气"),
                        UnifiedMessage.builder()
                                .role("assistant")
                                .toolCalls(List.of(UnifiedToolCall.builder()
                                        .id("toolu_1")
                                        .name("weather_query")
                                        .arguments("{\"city\":\"北京\"}")
                                        .build()))
                                .build(),
                        UnifiedMessage.tool("toolu_1", "晴 25 度")))
                .build();
        adapter().chat(request);

        JsonNode messages = readReceivedBody().get("messages");
        JsonNode assistant = messages.get(1);
        assertEquals("assistant", assistant.get("role").asText());
        JsonNode toolUse = assistant.get("content").get(0);
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("toolu_1", toolUse.get("id").asText());
        // input 必须是对象，统一层存的是字符串
        assertTrue(toolUse.get("input").isObject(), "tool_use.input 必须是对象");
        assertEquals("北京", toolUse.get("input").get("city").asText());

        JsonNode toolResult = messages.get(2);
        assertEquals("user", toolResult.get("role").asText(), "tool 结果要转成 user 角色");
        JsonNode block = toolResult.get("content").get(0);
        assertEquals("tool_result", block.get("type").asText());
        assertEquals("toolu_1", block.get("tool_use_id").asText());
        assertEquals("晴 25 度", block.get("content").asText());
    }

    /**
     * 非流式映射：content 是 block 数组，text 块拼成正文，tool_use 块转统一调用；
     * thinking 块不进正文（避免把思考当答案回显）。
     */
    @Test
    @DisplayName("非流式解析 content blocks 与 stop_reason")
    void parsesNonStreamingContentBlocks() {
        respondJson(MESSAGES_PATH, """
                {"type":"message","id":"msg_1","stop_reason":"tool_use",
                 "content":[
                   {"type":"thinking","thinking":"内部推理不应回显"},
                   {"type":"text","text":"我查一下"},
                   {"type":"tool_use","id":"toolu_9","name":"weather_query","input":{"city":"上海"}}
                 ],
                 "usage":{"input_tokens":20,"output_tokens":8}}
                """);

        UnifiedChatResponse response = adapter().chat(minimalRequest());

        assertEquals("我查一下", response.getMessage().getContent(), "thinking 块不得混入正文");
        assertEquals(1, response.getToolCalls().size());
        assertEquals("toolu_9", response.getToolCalls().get(0).getId());
        assertEquals("{\"city\":\"上海\"}", response.getToolCalls().get(0).getArguments());
        assertEquals(UnifiedFinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(20, response.getUsage().getPrompt());
        assertEquals(28, response.getUsage().getTotal(), "Anthropic 不给 total，由两项相加");
    }

    /**
     * baseUrl 已含 /v1 时不能重复叠加版本段。
     */
    @Test
    @DisplayName("baseUrl 已带 /v1 时不重复叠加")
    void doesNotDuplicateVersionSegment() {
        respondStreaming(MESSAGES_PATH, dataFrame("{\"type\":\"message_stop\"}"));

        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .baseUrl(baseUrl + "/v1")
                .apiKey(FAKE_KEY)
                .modelName("claude-test")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
        List<UnifiedStreamEvent> events = new AnthropicAdapter(config, client(), JSON)
                .chatStream(minimalRequest()).collectList().block(Duration.ofSeconds(10));

        assertNotNull(events);
        assertSingleDoneAtEnd(events);
        assertEquals("claude-test", readReceivedBody().get("model").asText());
    }

    // ---------- 以下为测试辅助 ----------

    /**
     * @return 指向假上游的 Anthropic Adapter
     */
    private AnthropicAdapter adapter() {
        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .providerName("anthropic")
                .baseUrl(baseUrl)
                .apiKey(FAKE_KEY)
                .modelName("test-model")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
        return new AnthropicAdapter(config, client(), JSON);
    }

    /**
     * @return 最小非流式请求
     */
    private UnifiedChatRequest minimalRequest() {
        return UnifiedChatRequest.builder()
                .messages(List.of(UnifiedMessage.user("hi")))
                .build();
    }

    /**
     * @return 解析后的最近一次请求体
     */
    private JsonNode readReceivedBody() {
        try {
            return JSON.readTree(receivedBody.get());
        } catch (Exception ex) {
            throw new AssertionError("请求体应为合法 JSON", ex);
        }
    }
}
