package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.impl.OpenAICompatibleAdapter;
import com.agenthub.runtime.model.acl.impl.OpenAICompatibleConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OCR 出站报文形态单测。所属域：runtime 的 infra。
 *
 * <p>守的是什么：探活走 {@code VisionProbePayload.openAiContent()}（{@code type=text} +
 * {@code type=image_url}，URL 为 {@code data:image/…;base64,…}），而 OCR 走
 * AgentScope → {@link AcpRequestMapper} → ACL → {@code OpenAiRequestMapper} 这条完全不同的链路。
 * 两条链路一旦产出不同形态（content 退化成字符串、image_url 缺 url、mime 写成 image/jpg），
 * 表现就是「探活绿、对话 400」，而且从状态码看不出任何线索。
 *
 * <p>约束：本地假上游 + 内存桩，零外网、零真实 Key。
 */
class VisionOcrOutboundTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 1x1 PNG 的 data URI，与探活用同一份素材形态 */
    private static final String PNG_URI = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private com.sun.net.httpserver.HttpServer server;
    private String baseUrl;

    /**
     * 本类只在 400 用例里起假上游；其余用例走内存桩，端口随机避免相互干扰。
     *
     * @throws IOException 端口绑定失败
     */
    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * @throws IOException 关停失败
     */
    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * OCR 出站必须是多模态数组：content 为 [{type:text},{type:image_url}]，且 url 是 data URL。
     */
    @Test
    void ocrOutboundUsesMultimodalArrayWithDataUrl() throws Exception {
        String body = outboundBody(PNG_URI);

        var root = JSON.readTree(body);
        var messages = root.get("messages");
        assertNotNull(messages, "必须带 messages");
        assertEquals(1, messages.size(), "单图一次调用只应有一条 user 消息");

        var content = messages.get(0).get("content");
        assertTrue(content.isArray(), "content 必须是数组，纯字符串无法携带 image_url：" + content);

        var textPart = content.get(0);
        assertEquals("text", textPart.get("type").asText());
        assertFalse(textPart.get("text").asText().isBlank(), "文字段不能为空，空段部分厂商直接 400");

        var imagePart = content.get(1);
        assertEquals("image_url", imagePart.get("type").asText());
        String url = imagePart.get("image_url").get("url").asText();
        assertTrue(url.startsWith("data:image/"), "图片必须是 data URL：" + url);
        assertTrue(url.contains(";base64,"), "data URL 必须声明 base64：" + url);

        // 退化成纯文本 = 探活过、OCR 必然失败的那种回归，这里显式挡住
        assertFalse(messages.get(0).get("content").isTextual(), "禁止退化成纯文本 content");
    }

    /**
     * 非标准 MIME 别名与夹带空白的 base64 都要被规整成厂商可接受形态。
     */
    @Test
    void normalizesJpgAliasAndWhitespaceInBase64() throws Exception {
        String messy = "data:image/jpg;base64, iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB\n"
                + "CAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg== ";

        String body = outboundBody(messy);
        String url = JSON.readTree(body).get("messages").get(0).get("content").get(1)
                .get("image_url").get("url").asText();

        assertTrue(url.startsWith("data:image/jpeg;base64,"), "image/jpg 应改成 image/jpeg：" + url);
        String payload = url.substring(url.indexOf(";base64,") + ";base64,".length());
        assertFalse(payload.contains("\n") || payload.contains(" "), "base64 不应含空白：" + payload);
    }

    /**
     * 未配置时出站 max_tokens 必须是 1024：多数视觉模型只接受 [1,1024]，2048 会被直接 400。
     */
    @Test
    void defaultMaxTokensIs1024() throws Exception {
        assertEquals(1024, JSON.readTree(outboundBody(PNG_URI)).get("max_tokens").asInt());
    }

    /**
     * 实例显式配了更大值时原样发出，不做上限钳制。
     */
    @Test
    void explicitLargerMaxTokensIsSentAsIs() throws Exception {
        assertEquals(4096, JSON.readTree(outboundBody(PNG_URI, 4096)).get("max_tokens").asInt());
        assertEquals(2048, JSON.readTree(outboundBody(PNG_URI, 2048)).get("max_tokens").asInt(),
                "2048 也不得被钳到 1024");
    }

    /**
     * 真实 400 链路：异常链（也就是日志与追踪里的 cause）必须带状态码与脱敏 body 摘要。
     */
    @Test
    void badRequestCauseCarriesStatusAndSanitizedBodyBrief() {
        // 假上游回 400，body 里同时夹带错误原因与一个 Key，验证「原因可见、Key 不可见」
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = ("{\"error\":{\"message\":\"image too large\","
                    + "\"code\":\"invalid_parameter\"},"
                    + "\"echo\":{\"authorization\":\"Bearer sk-abcdef123456\","
                    + "\"image\":\"data:image/png;base64," + "A".repeat(300) + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        OpenAICompatibleConfig config = OpenAICompatibleConfig.builder()
                .providerName("vision-test")
                .baseUrl(baseUrl)
                .apiKey("sk-test-not-real")
                .modelName("qwen-vl-max")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
        ModelProviderAdapter real = new OpenAICompatibleAdapter(
                config, WebClient.builder().baseUrl(baseUrl).build(), JSON);

        VisionOcrExecutor executor = new VisionOcrExecutor(factory(bridge(real)), command -> { });
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> executor.extract(List.of(PNG_URI), 16L, MeterScope.console(1L, 16L, 2L, 3L)));

        StringBuilder chain = new StringBuilder();
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            chain.append(cursor.getMessage()).append(" | ");
            if (cursor.getCause() == cursor) {
                break;
            }
        }
        String text = chain.toString();
        assertTrue(text.contains("400"), "cause 必须带状态码：" + text);
        assertTrue(text.contains("image too large"), "cause 必须带脱敏 body 摘要：" + text);
        assertFalse(text.contains("sk-abcdef123456"), "cause 不得带 Key：" + text);
        assertFalse(text.contains("A".repeat(40)), "cause 不得带长 base64：" + text);
    }

    // ---------- 以下为测试辅助 ----------

    /**
     * 走真实 OCR 出站点，抓最终会发给厂商的请求体 JSON。
     *
     * @param dataUri 待识别图片
     * @return 请求体 JSON 文本
     */
    private String outboundBody(String dataUri) throws Exception {
        return outboundBody(dataUri, null);
    }

    /**
     * 同上，可指定实例配置的 max_tokens。
     *
     * @param dataUri   待识别图片
     * @param maxTokens 实例配置值，null 表示未配置
     * @return 请求体 JSON 文本
     */
    private String outboundBody(String dataUri, Integer maxTokens) throws Exception {
        CapturingAdapter adapter = new CapturingAdapter(Flux.empty());
        VisionOcrExecutor executor = new VisionOcrExecutor(factory(bridge(adapter), maxTokens), command -> { });
        try {
            executor.extract(List.of(dataUri), 16L, MeterScope.console(1L, 16L, 2L, 3L));
        } catch (RuntimeException ignored) {
            // 桩不发事件，必然失败；这里只关心出站报文
        }
        assertNotNull(adapter.captured, "Adapter 应收到请求");
        return buildBody(adapter.captured);
    }

    /**
     * 用生产的 ACL 请求映射器生成真实出站 JSON，而不是自己拼字符串。
     *
     * @param request 统一请求
     * @return 请求体 JSON
     */
    private String buildBody(UnifiedChatRequest request) throws Exception {
        Class<?> type = Class.forName("com.agenthub.runtime.model.acl.impl.OpenAiRequestMapper");
        Constructor<?> ctor = type.getDeclaredConstructor(ObjectMapper.class);
        ctor.setAccessible(true);
        Object mapper = ctor.newInstance(JSON);
        Method build = type.getDeclaredMethod("build", UnifiedChatRequest.class, String.class, boolean.class);
        build.setAccessible(true);
        return (String) build.invoke(mapper, request, "qwen-vl-max", true);
    }

    /**
     * @return 指向给定适配器的引擎模型桥（AgentScope 模型接口）
     */
    private Model bridge(ModelProviderAdapter adapter) {
        try {
            Constructor<?> ctor = Class.forName("com.agenthub.runtime.infra.agentscope.AcpModelBridge")
                    .getDeclaredConstructor(ModelProviderAdapter.class, String.class, ObjectMapper.class,
                            boolean.class);
            ctor.setAccessible(true);
            return (Model) ctor.newInstance(adapter, "qwen-vl-max", JSON, true);
        } catch (Exception ex) {
            throw new IllegalStateException("构造桥接失败", ex);
        }
    }

    /**
     * @return 只覆写 open / maxTokensOf 的模型工厂，避免触发真实凭证解密
     */
    private AgentScopeModelFactory factory(Model model) {
        return factory(model, null);
    }

    /**
     * @param model       视觉模型
     * @param maxTokens   实例 extraJson 里配的 max_tokens，null 表示未配置
     * @return 桩工厂
     */
    private AgentScopeModelFactory factory(Model model, Integer maxTokens) {
        return new AgentScopeModelFactory(null, null, null, null, null, JSON) {
            @Override
            public Model open(Long instanceId) {
                return model;
            }

            @Override
            public Integer maxTokensOf(Long instanceId) {
                return maxTokens;
            }
        };
    }

    /**
     * 捕获出站请求、按预置流应答的适配器桩。
     */
    private static final class CapturingAdapter implements ModelProviderAdapter {
        private final Flux<UnifiedStreamEvent> events;
        private UnifiedChatRequest captured;

        CapturingAdapter(Flux<UnifiedStreamEvent> events) {
            this.events = events;
        }

        @Override
        public String getProviderName() {
            return "vision-test";
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
