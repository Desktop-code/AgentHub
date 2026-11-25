package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容 Adapter 非流式解析单测。所属域：runtime（ACL 实现）。
 * 约束：本地假上游，不打真实模型 API；同时校验出站请求体映射（tools 位置、tool 角色回灌字段）。
 */
class OpenAICompatibleAdapterChatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private com.sun.net.httpserver.HttpServer server;
    private String baseUrl;
    /** 记录上游收到的请求体，用于断言出站映射 */
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    /**
     * 启动本地假上游。
     */
    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 关停假上游。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 非流式：正文、finish_reason、usage 都要正确映射。
     */
    @Test
    @DisplayName("非流式解析正文、结束原因与 usage")
    void parsesContentFinishReasonAndUsage() {
        respond("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16}}
                """);

        UnifiedChatResponse response = adapter().chat(request());

        assertEquals("你好", response.getMessage().getContent());
        assertEquals("assistant", response.getMessage().getRole());
        assertEquals(UnifiedFinishReason.STOP, response.getFinishReason());
        assertEquals(11, response.getUsage().getPrompt());
        assertEquals(16, response.getUsage().getTotal());
        assertTrue(response.getToolCalls().isEmpty());
    }

    /**
     * 非流式工具调用：arguments 是完整字符串，finish_reason 应为 tool_calls。
     */
    @Test
    @DisplayName("非流式解析工具调用并判为 tool_calls")
    void parsesToolCalls() {
        respond("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_x","type":"function",
                 "function":{"name":"weather_query","arguments":"{\\"city\\":\\"上海\\"}"}}]},
                 "finish_reason":"tool_calls"}],"usage":null}
                """);

        UnifiedChatResponse response = adapter().chat(request());

        assertEquals(1, response.getToolCalls().size());
        assertEquals("call_x", response.getToolCalls().get(0).getId());
        assertEquals("weather_query", response.getToolCalls().get(0).getName());
        assertEquals("{\"city\":\"上海\"}", response.getToolCalls().get(0).getArguments());
        assertEquals(UnifiedFinishReason.TOOL_CALLS, response.getFinishReason());
        // usage 缺失时保持 null，不伪造 0
        assertNull(response.getUsage());
    }

    /**
     * 出站映射：tools 必须是 tools[].function.parameters 结构，
     * system 消息留在 messages 里（OpenAI 兼容族没有独立 system 字段）。
     */
    @Test
    @DisplayName("出站请求把工具定义映射到 tools[].function")
    void mapsToolsIntoRequest() {
        respond("{\"choices\":[{\"index\":0,\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");

        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .messages(List.of(UnifiedMessage.system("你是助手"), UnifiedMessage.user("hi")))
                .tools(List.of(com.agenthub.runtime.model.acl.UnifiedToolDef.builder()
                        .name("weather_query")
                        .description("查天气")
                        .parametersJsonSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                        .build()))
                .temperature(0.3)
                .maxTokens(256)
                .build();

        adapter().chat(request);

        try {
            JsonNode sent = JSON.readTree(receivedBody.get());
            assertEquals("test-model", sent.get("model").asText());
            assertEquals(false, sent.get("stream").asBoolean());
            assertEquals(0.3, sent.get("temperature").asDouble());
            assertEquals(256, sent.get("max_tokens").asInt());
            assertEquals("system", sent.get("messages").get(0).get("role").asText());
            JsonNode tool = sent.get("tools").get(0);
            assertEquals("function", tool.get("type").asText());
            assertEquals("weather_query", tool.get("function").get("name").asText());
            // parameters 必须是对象而非字符串
            assertTrue(tool.get("function").get("parameters").isObject());
            assertEquals("object", tool.get("function").get("parameters").get("type").asText());
        } catch (Exception ex) {
            throw new AssertionError("请求体应为合法 JSON", ex);
        }
    }

    /**
     * 出站映射：tool 结果消息必须带 tool_call_id，否则上游会 400。
     */
    @Test
    @DisplayName("出站请求回灌 tool 消息时带 tool_call_id")
    void mapsToolResultMessageWithCallId() {
        respond("{\"choices\":[{\"index\":0,\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");

        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .messages(List.of(
                        UnifiedMessage.user("北京天气"),
                        UnifiedMessage.tool("call_x", "晴 25 度")))
                .build();

        adapter().chat(request);

        try {
            JsonNode sent = JSON.readTree(receivedBody.get());
            JsonNode toolMessage = sent.get("messages").get(1);
            assertEquals("tool", toolMessage.get("role").asText());
            assertEquals("call_x", toolMessage.get("tool_call_id").asText());
            assertEquals("晴 25 度", toolMessage.get("content").asText());
            // 无 tools 时不应出现空数组字段
            assertNull(sent.get("tools"), "无工具时不应下发空 tools 数组");
        } catch (Exception ex) {
            throw new AssertionError("请求体应为合法 JSON", ex);
        }
    }

    // ---------- 辅助 ----------

    /**
     * 组装 Adapter，指向本地假上游。
     */
    private OpenAICompatibleAdapter adapter() {
        OpenAICompatibleConfig config = OpenAICompatibleConfig.builder()
                .providerName("test")
                .baseUrl(baseUrl)
                .apiKey("sk-test-not-real")
                .modelName("test-model")
                .build();
        return new OpenAICompatibleAdapter(config, WebClient.builder().baseUrl(baseUrl).build(), JSON);
    }

    /**
     * @return 最小请求
     */
    private UnifiedChatRequest request() {
        return UnifiedChatRequest.builder()
                .messages(List.of(UnifiedMessage.user("hi")))
                .build();
    }

    /**
     * 注册一个 JSON 响应，并记录请求体。
     */
    private void respond(String json) {
        server.createContext("/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
