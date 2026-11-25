package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容 Adapter 的流式核心单测。所属域：runtime（ACL 实现）。
 * 约束：用 JDK 自带 HttpServer 起本地假上游，<b>不访问真实模型 API</b>，也不依赖 okhttp/MockWebServer
 * （工程 classpath 上已同时存在 okhttp 4.12 与 5.3.2，再引测试替身会加剧版本冲突）。
 * 覆盖点：tool_call 碎片按 index 拼成完整 JSON、流断/缺 [DONE]/上游报错 三种情况都必补 done。
 */
class OpenAICompatibleAdapterStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private com.sun.net.httpserver.HttpServer server;
    private String baseUrl;

    /**
     * 每个用例起一个本地假上游，端口随机，避免相互干扰。
     */
    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 关停假上游，释放端口。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 核心用例：三段 arguments 碎片必须按 index 拼成一个完整 JSON，
     * 并且只发一次 completed，最终以 done 结束。
     */
    @Test
    @DisplayName("流式 tool_calls 碎片按 index 拼成完整参数后发 completed")
    void streamToolCallFragmentsAssembleIntoCompleteJson() {
        // 模拟真实上游：id/name 只在首帧出现，arguments 被切三段（含跨帧的汉字与 JSON 结构）
        String body = toolCallChunk(0, "call_abc", "weather_query", "", null)
                + toolCallChunk(0, null, null, "{\"city\":\"北", null)
                + toolCallChunk(0, null, null, "京\",\"unit\":\"c\"}", null)
                + finishChunk("tool_calls")
                + "data: [DONE]\n\n";
        respondStreaming(body);

        List<UnifiedStreamEvent> events = collect(adapter());

        // completed 必须恰好一个，且参数是完整 JSON
        List<UnifiedStreamEvent> completed = events.stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                .toList();
        assertEquals(1, completed.size(), "碎片应合并为一次 completed");
        UnifiedToolCall call = completed.get(0).getToolCallDelta();
        assertEquals("call_abc", call.getId());
        assertEquals("weather_query", call.getName());
        assertEquals("{\"city\":\"北京\",\"unit\":\"c\"}", call.getArguments());
        // 拼出来的参数必须能被反序列化，证明不是半截文本
        assertTrue(isValidJson(call.getArguments()), "completed 参数必须是合法 JSON");

        // 结尾必须是 done 且原因正确
        assertEquals(UnifiedStreamEventType.DONE, lastType(events));
        assertEquals(UnifiedFinishReason.TOOL_CALLS, events.get(events.size() - 1).getFinishReason());
    }

    /**
     * 多工具并发：不同 index 的碎片交错到达，不能串到同一个调用里。
     */
    @Test
    @DisplayName("两个工具调用交错分片时按 index 各自归组")
    void interleavedToolCallsStaySeparatedByIndex() {
        String body = toolCallChunk(0, "call_a", "tool_a", "{\"x\"", null)
                + toolCallChunk(1, "call_b", "tool_b", "{\"y\"", null)
                + toolCallChunk(0, null, null, ":1}", null)
                + toolCallChunk(1, null, null, ":2}", null)
                + finishChunk("tool_calls")
                + "data: [DONE]\n\n";
        respondStreaming(body);

        List<UnifiedToolCall> calls = collect(adapter()).stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                .map(UnifiedStreamEvent::getToolCallDelta)
                .toList();

        assertEquals(2, calls.size());
        assertEquals("call_a", calls.get(0).getId());
        assertEquals("{\"x\":1}", calls.get(0).getArguments());
        assertEquals("call_b", calls.get(1).getId());
        assertEquals("{\"y\":2}", calls.get(1).getArguments());
    }

    /**
     * 上游直接断开、既没有 [DONE] 也没有 finish_reason：仍必须有 done 收尾。
     */
    @Test
    @DisplayName("流被截断且无 [DONE] 时仍补 done")
    void truncatedStreamStillEmitsDone() {
        // 只有正文增量，随后连接直接结束
        respondStreaming("data: " + content("你") + "\n\n");
        // 注意此处刻意不写 [DONE]

        List<UnifiedStreamEvent> events = collect(adapter());

        assertTrue(events.stream().anyMatch(e -> e.getType() == UnifiedStreamEventType.MESSAGE_DELTA),
                "应保留已到达的正文增量");
        assertEquals(UnifiedStreamEventType.DONE, lastType(events), "截断流必须补 done");
        assertEquals(UnifiedFinishReason.STOP, events.get(events.size() - 1).getFinishReason());
    }

    /**
     * 上游返回 500：先 error 再 done，且文案不含响应体原文。
     */
    @Test
    @DisplayName("上游 5xx 时先发 error 再补 done")
    void upstreamErrorEmitsErrorThenDone() {
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "{\"error\":\"internal secret detail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        List<UnifiedStreamEvent> events = collect(adapter());

        assertEquals(UnifiedStreamEventType.ERROR, events.get(0).getType());
        assertEquals(UnifiedStreamEventType.DONE, lastType(events));
        assertEquals(UnifiedFinishReason.ERROR, events.get(events.size() - 1).getFinishReason());
        // 脱敏：不得把上游响应体带出来
        assertFalse(events.get(0).getContent().contains("secret"), "错误摘要不得包含响应体原文");
    }

    /**
     * 正常文本流 + usage：usage 只发一次，done 带同一个 usage。
     */
    @Test
    @DisplayName("正文增量与 usage 正常映射，done 收尾且 usage 不重复")
    void contentAndUsageMappedOnce() {
        String usageFrame = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}\n\n";
        respondStreaming(
                "data: " + content("你好") + "\n\n"
                        + "data: " + content("，世界") + "\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + usageFrame
                        + "data: [DONE]\n\n");

        List<UnifiedStreamEvent> events = collect(adapter());

        String text = events.stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.MESSAGE_DELTA)
                .map(UnifiedStreamEvent::getContent)
                .reduce("", String::concat);
        assertEquals("你好，世界", text);

        assertEquals(1, events.stream().filter(e -> e.getType() == UnifiedStreamEventType.USAGE).count(),
                "usage 事件只应发一次");
        UnifiedStreamEvent done = events.get(events.size() - 1);
        assertEquals(UnifiedStreamEventType.DONE, done.getType());
        assertEquals(UnifiedFinishReason.STOP, done.getFinishReason());
        assertNotNull(done.getUsage());
        assertEquals(10, done.getUsage().getTotal());
    }

    /**
     * 跨 buffer 的 UTF-8 多字节字符不能被截断成乱码。
     */
    @Test
    @DisplayName("多字节字符被拆到两个 TCP 包时不出现乱码")
    void multibyteCharacterSplitAcrossBuffers() {
        byte[] payload = ("data: " + content("中文测试") + "\n\n" + "data: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            // 在第一个汉字（3 字节）中间断开，逼迫实现处理跨包残字节
            int split = indexOfSecondDataStart(payload);
            out.write(payload, 0, split);
            out.flush();
            try {
                Thread.sleep(30);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            out.write(payload, split, payload.length - split);
            out.flush();
            out.close();
        });

        String text = collect(adapter()).stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.MESSAGE_DELTA)
                .map(UnifiedStreamEvent::getContent)
                .reduce("", String::concat);

        assertEquals("中文测试", text);
    }

    // ---------- 以下为测试辅助 ----------

    /**
     * 在 data: 前缀之后的第一个汉字字节中间切一刀，确保跨包。
     */
    private int indexOfSecondDataStart(byte[] payload) {
        // "data: " 是 6 字节，再往后偏 1 字节即落在第一个汉字中间
        return 7;
    }

    /**
     * 组装一个完整的 Adapter，指向本地假上游。
     */
    private OpenAICompatibleAdapter adapter() {
        OpenAICompatibleConfig config = OpenAICompatibleConfig.builder()
                .providerName("test")
                .baseUrl(baseUrl)
                .apiKey("sk-test-not-real")
                .modelName("test-model")
                .streamIdleTimeout(java.time.Duration.ofSeconds(5))
                .build();
        WebClient client = WebClient.builder().baseUrl(baseUrl).build();
        return new OpenAICompatibleAdapter(config, client, JSON);
    }

    /**
     * 发一个请求并同步收集全部事件。用阻塞收集是为了让断言简单；
     * 超时保护避免用例在缺 done 时永久挂起——这本身也是对「必须有 done」的检验。
     */
    private List<UnifiedStreamEvent> collect(OpenAICompatibleAdapter adapter) {
        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .stream(true)
                .messages(List.of(UnifiedMessage.user("hi")))
                .build();
        return adapter.chatStream(request)
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
    }

    /**
     * 注册一个一次性 SSE 响应，body 一次性写完再关闭。
     */
    private void respondStreaming(String body) {
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        });
    }

    /**
     * @return 正文增量帧的 data 内容
     */
    private static String content(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}]}";
    }

    /**
     * @return 原始 JSON 帧（不带 data: 前缀），tool_calls 按规范嵌在 delta.tool_calls[] 里
     */
    private static String chunk(String deltaJson, String finishReason) {
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "data: {\"choices\":[{\"index\":0,\"delta\":" + deltaJson + ",\"finish_reason\":" + finish + "}]}\n\n";
    }

    /**
     * 构造一帧 tool_calls 碎片。按 OpenAI 规范：index/function 嵌在 delta.tool_calls 数组元素内，
     * id 与 name 通常只在首帧出现，arguments 是逐段拼的字符串。
     *
     * @param toolIndex       归组下标
     * @param id              调用 id，后续帧传 null
     * @param name            工具名，后续帧传 null
     * @param argumentsJson   参数原文（已转义），null 表示本帧不带参数
     * @param finishReason    结束原因，null 表示中间帧
     * @return 完整的 SSE 帧文本
     */
    private static String toolCallChunk(int toolIndex, String id, String name, String argumentsJson, String finishReason) {
        StringBuilder delta = new StringBuilder("{\"tool_calls\":[{\"index\":").append(toolIndex);
        if (id != null) {
            delta.append(",\"id\":\"").append(id).append("\",\"type\":\"function\"");
        }
        delta.append(",\"function\":{");
        boolean needComma = false;
        if (name != null) {
            delta.append("\"name\":\"").append(name).append("\"");
            needComma = true;
        }
        if (argumentsJson != null) {
            if (needComma) {
                delta.append(',');
            }
            delta.append("\"arguments\":\"").append(escape(argumentsJson)).append("\"");
        }
        delta.append("}}]}");
        return chunk(delta.toString(), finishReason);
    }

    /**
     * 构造一个只带 finish_reason、不带增量的收尾帧。
     */
    private static String finishChunk(String finishReason) {
        return chunk("{}", finishReason);
    }

    /**
     * 把参数原文转义成可放进 JSON 字符串的形式。
     */
    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * @return 最后一个事件的类型
     */
    private static UnifiedStreamEventType lastType(List<UnifiedStreamEvent> events) {
        return events.get(events.size() - 1).getType();
    }

    /**
     * @return 文本是否可被解析为 JSON 对象
     */
    private static boolean isValidJson(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node != null && node.isObject();
        } catch (Exception ex) {
            return false;
        }
    }
}
