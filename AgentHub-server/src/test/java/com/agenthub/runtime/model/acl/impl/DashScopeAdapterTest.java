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
 * DashScope 原生协议 Adapter 单测。所属域：runtime（ACL 实现）。
 * 覆盖：原生流式碎片拼装、思考字段、tool_call 按 id 归组、缺结束标识的截断兜底、
 * 上游报错脱敏、非流式映射与出站请求结构（input/parameters 嵌套）。
 * 约束：全部走本地假上游，零真实外网、零真实 Key。
 */
class DashScopeAdapterTest extends AclAdapterTestSupport {

    /** 原生文本生成接口的固定路径 */
    private static final String NATIVE_PATH = "/api/v1/services/aigc/text-generation/generation";

    /**
     * 正常文本流：增量帧拼成完整正文，usage 只发一次，done 收尾。
     */
    @Test
    @DisplayName("原生流式正文增量拼合，usage 只发一次并以 done 收尾")
    void streamsContentAndUsage() {
        respondStreaming(NATIVE_PATH, String.join("",
                dataFrame(frame("你好", null)),
                dataFrame(frame("，世界", null)),
                dataFrame(frame(null, "stop")),
                dataFrame("{\"output\":{\"choices\":[]},\"usage\":{\"input_tokens\":9,\"output_tokens\":4,\"total_tokens\":13}}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals("你好，世界", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        assertEquals(1, events.stream().filter(e -> e.getType() == UnifiedStreamEventType.USAGE).count(),
                "usage 事件只应发一次");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.STOP, done.getFinishReason());
        assertNotNull(done.getUsage());
        assertEquals(13, done.getUsage().getTotal());
    }

    /**
     * 思考字段：原生用 reasoning_content，与 OpenAI 的 reasoning_content 同名但位置在 output.choices[].message，
     * 必须映射成 thinking.delta 而不是混进正文。
     */
    @Test
    @DisplayName("reasoning_content 映射为 thinking.delta 且不混入正文")
    void mapsReasoningContentToThinking() {
        respondStreaming(NATIVE_PATH, String.join("",
                dataFrame("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"reasoning_content\":\"先想\"}}]}}"),
                dataFrame("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"reasoning_content\":\"一下\"}}]}}"),
                dataFrame(frame("答案", "stop"))));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals("先想一下", concatContent(events, UnifiedStreamEventType.THINKING_DELTA));
        assertEquals("答案", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        assertSingleDoneAtEnd(events);
    }

    /**
     * 工具调用碎片：原生接口不带 index，只能按 id 归组；参数被切成三段 + 一个空 id 的续帧，
     * 必须拼成完整 JSON 且只发一次 completed。
     */
    @Test
    @DisplayName("tool_calls 碎片按 id 归组，参数拼完整后只发一次 completed")
    void assemblesToolCallFragmentsById() {
        respondStreaming(NATIVE_PATH, String.join("",
                dataFrame(toolCallFrame("call_1", "weather_query", "")),
                dataFrame(toolCallFrame(null, null, "{\"city\":\"北")),
                dataFrame(toolCallFrame(null, null, "京\",\"unit\":\"c\"}")),
                dataFrame("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\"},\"finish_reason\":\"tool_calls\"}]}}")));

        List<UnifiedStreamEvent> events = collect(adapter());

        List<UnifiedToolCall> completed = events.stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                .map(UnifiedStreamEvent::getToolCallDelta)
                .toList();
        assertEquals(1, completed.size(), "碎片应合并为一次 completed，实际 " + completed.size());
        assertEquals("call_1", completed.get(0).getId());
        assertEquals("weather_query", completed.get(0).getName());
        assertEquals("{\"city\":\"北京\",\"unit\":\"c\"}", completed.get(0).getArguments());
        assertTrue(isValidJsonObject(completed.get(0).getArguments()), "completed 参数必须是合法 JSON");

        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.TOOL_CALLS, done.getFinishReason());
    }

    /**
     * 关键差异化场景：原生接口<b>没有 [DONE] 标记</b>，靠 EOF 收尾。
     * 上游只推了正文就断开时，仍必须有且仅有一个 done。
     */
    @Test
    @DisplayName("原生协议无 [DONE]，EOF 截断时仍补 done")
    void truncatedNativeStreamStillEmitsSingleDone() {
        respondStreaming(NATIVE_PATH, dataFrame(frame("半截", null)));

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals("半截", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        assertSingleDoneAtEnd(events);
    }

    /**
     * 上游 5xx：先 error 再 done，且文案不含响应体原文（响应体里放了假的凭证片段）。
     */
    @Test
    @DisplayName("上游 5xx 时先发脱敏 error 再补 done")
    void upstreamErrorEmitsRedactedErrorThenDone() {
        respondError(NATIVE_PATH, 500, "{\"code\":\"InternalError\",\"secret\":\"sk-upstream-leak\"}");

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals(UnifiedStreamEventType.ERROR, events.get(0).getType());
        assertFalse(events.get(0).getContent().contains("sk-upstream-leak"),
                "错误摘要不得包含响应体原文");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.ERROR, done.getFinishReason());
    }

    /**
     * 读空闲超时：上游推完一帧就卡住不动，必须有 error + done(TIMEOUT)，
     * 不能一直挂着让前端 Loading。这是「五种收尾路径」里最容易被漏的一条。
     */
    @Test
    @DisplayName("上游卡住不推数据时超时收尾，error 与 done 都是 TIMEOUT")
    void idleUpstreamEndsWithTimeout() {
        // 先给一帧正文，再停住 1.5s；Adapter 的空闲超时设为 300ms，必然先触发超时
        respondStalling(NATIVE_PATH, dataFrame(frame("开头", null)), 1500);

        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .providerName("dashscope")
                .baseUrl(baseUrl)
                .apiKey(FAKE_KEY)
                .modelName("test-model")
                .streamIdleTimeout(Duration.ofMillis(300))
                .build();
        List<UnifiedStreamEvent> events = new DashScopeAdapter(config, client(), JSON)
                .chatStream(minimalRequest()).collectList().block(Duration.ofSeconds(10));

        assertNotNull(events);
        assertEquals("开头", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA),
                "超时前到达的正文必须保留");
        assertTrue(events.stream().anyMatch(e -> e.getType() == UnifiedStreamEventType.ERROR),
                "超时也要有 error 事件供前端提示");
        UnifiedStreamEvent done = assertSingleDoneAtEnd(events);
        assertEquals(UnifiedFinishReason.TIMEOUT, done.getFinishReason());
    }

    /**
     * 出站请求结构：原生协议要求 messages 包在 input 里、采样与 tools 包在 parameters 里，
     * 且必须带 X-DashScope-SSE 头，否则上游会返回一次性响应。
     */
    @Test
    @DisplayName("出站请求为 input/parameters 嵌套结构并开启 SSE 头")
    void buildsNativeRequestShape() {
        respondStreaming(NATIVE_PATH, dataFrame(frame("ok", "stop")));

        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .stream(true)
                .messages(List.of(UnifiedMessage.system("你是助手"), UnifiedMessage.user("hi")))
                .tools(List.of(UnifiedToolDef.builder()
                        .name("weather_query")
                        .description("查天气")
                        .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                        .build()))
                .temperature(0.2)
                .maxTokens(128)
                .build();
        adapter().chatStream(request).collectList().block(Duration.ofSeconds(10));

        assertNotNull(receivedHeaders.get(), "应记录到请求头");
        assertEquals("enable", receivedHeaders.get().getFirst("X-DashScope-SSE"),
                "流式必须显式开启 SSE，否则拿不到增量");
        assertEquals("Bearer " + FAKE_KEY, receivedHeaders.get().getFirst("Authorization"),
                "原生协议用 Bearer 鉴权");

        JsonNode sent = readReceivedBody();
        assertEquals("test-model", sent.get("model").asText());
        // messages 必须在 input 下，不在根上
        assertNull(sent.get("messages"), "原生协议不允许把 messages 放在根上");
        assertEquals("system", sent.get("input").get("messages").get(0).get("role").asText());
        JsonNode parameters = sent.get("parameters");
        assertEquals("message", parameters.get("result_format").asText(),
                "必须要求 message 格式，否则拿不到工具调用");
        assertTrue(parameters.get("incremental_output").asBoolean(), "流式需开增量输出");
        assertEquals(0.2, parameters.get("temperature").asDouble());
        assertEquals(128, parameters.get("max_tokens").asInt());
        // tools 在 parameters 内，且 parameters 里的 schema 是对象
        JsonNode tool = parameters.get("tools").get(0);
        assertEquals("weather_query", tool.get("function").get("name").asText());
        assertTrue(tool.get("function").get("parameters").isObject());
    }

    /**
     * 非流式映射：原生结构是 output.choices[0].message，usage 用 input/output_tokens。
     */
    @Test
    @DisplayName("非流式解析 output.choices 与 input/output_tokens")
    void parsesNonStreamingResponse() {
        respondJson(NATIVE_PATH, """
                {"request_id":"req-1","output":{"choices":[{"finish_reason":"stop",
                 "message":{"role":"assistant","content":"你好"}}]},
                 "usage":{"input_tokens":11,"output_tokens":5,"total_tokens":16}}
                """);

        UnifiedChatResponse response = adapter().chat(minimalRequest());

        assertEquals("你好", response.getMessage().getContent());
        assertEquals(UnifiedFinishReason.STOP, response.getFinishReason());
        assertEquals(11, response.getUsage().getPrompt());
        assertEquals(16, response.getUsage().getTotal());
    }

    /**
     * 非流式工具调用：input 结构里 tool_calls 也是完整对象，finish_reason=tool_calls。
     */
    @Test
    @DisplayName("非流式工具调用映射为统一 toolCalls")
    void parsesNonStreamingToolCalls() {
        respondJson(NATIVE_PATH, """
                {"output":{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                 "tool_calls":[{"id":"call_x","type":"function",
                 "function":{"name":"weather_query","arguments":"{\\"city\\":\\"上海\\"}"}}]}}]},
                 "usage":{"input_tokens":8,"output_tokens":6}}
                """);

        UnifiedChatResponse response = adapter().chat(minimalRequest());

        assertEquals(1, response.getToolCalls().size());
        assertEquals("call_x", response.getToolCalls().get(0).getId());
        assertEquals("weather_query", response.getToolCalls().get(0).getName());
        assertEquals("{\"city\":\"上海\"}", response.getToolCalls().get(0).getArguments());
        assertEquals(UnifiedFinishReason.TOOL_CALLS, response.getFinishReason());
    }

    /**
     * 兼容模式：供应商 baseUrl 含 compatible-mode 时语义就是 OpenAI 兼容协议，
     * Adapter 应内部委派，打到 /chat/completions 而不是原生生成接口。
     */
    @Test
    @DisplayName("compatible-mode 的 baseUrl 委派到 OpenAI 兼容路径")
    void compatibleModeDelegatesToOpenAiPath() {
        respondStreaming("/compatible-mode/v1/chat/completions",
                dataFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}") + "data: [DONE]\n\n");

        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .providerName("dashscope")
                .baseUrl(baseUrl + "/compatible-mode/v1")
                .apiKey(FAKE_KEY)
                .modelName("qwen-plus")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
        List<UnifiedStreamEvent> events = new DashScopeAdapter(config, client(), JSON)
                .chatStream(minimalRequest()).collectList().block(Duration.ofSeconds(10));

        assertNotNull(events);
        assertEquals("hi", concatContent(events, UnifiedStreamEventType.MESSAGE_DELTA));
        assertSingleDoneAtEnd(events);
    }

    // ---------- 以下为测试辅助 ----------

    /**
     * @return 指向假上游的原生模式 Adapter
     */
    private DashScopeAdapter adapter() {
        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .providerName("dashscope")
                .baseUrl(baseUrl)
                .apiKey(FAKE_KEY)
                .modelName("test-model")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
        return new DashScopeAdapter(config, client(), JSON);
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

    /**
     * 构造一帧原生流式响应：正文 + 结束原因。
     *
     * @param content     正文片段，null 表示本帧无正文
     * @param finishReason 结束原因，null 表示未完
     * @return 帧 JSON（不含 data: 前缀）
     */
    private static String frame(String content, String finishReason) {
        String finish = finishReason == null ? "\"null\"" : "\"" + finishReason + "\"";
        String contentField = content == null ? "" : ",\"content\":\"" + escape(content) + "\"";
        return "{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\"" + contentField
                + "},\"finish_reason\":" + finish + "}]}}";
    }

    /**
     * 构造一帧 tool_calls 碎片。原生接口不带 index，id 只在首帧出现。
     *
     * @param id        调用 id，续帧传 null
     * @param name      工具名，续帧传 null
     * @param arguments 参数原文片段
     * @return 帧 JSON（不含 data: 前缀）
     */
    private static String toolCallFrame(String id, String name, String arguments) {
        StringBuilder call = new StringBuilder("{");
        boolean needComma = false;
        if (id != null) {
            call.append("\"id\":\"").append(id).append("\",\"type\":\"function\"");
            needComma = true;
        }
        if (name != null) {
            if (needComma) {
                call.append(',');
            }
            call.append("\"function\":{\"name\":\"").append(name).append("\",\"arguments\":\"")
                    .append(escape(arguments)).append("\"}");
        } else {
            call.append("\"function\":{\"arguments\":\"").append(escape(arguments)).append("\"}");
        }
        call.append('}');
        return "{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + call + "]},\"finish_reason\":\"null\"}]}}";
    }
}
