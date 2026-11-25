package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelCapability;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地 HttpServer 代替外网，覆盖协议×用途探活矩阵（适配格真路径 / 未适配格无 Chat URL）。
 */
class ModelProbeClientTest {

    private HttpServer server;
    private ModelHttpSupport http;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    /**
     * 每个用例前起一个随机端口，记下实际访问路径。
     */
    @BeforeEach
    void setUp() throws IOException {
        path.set(null);
        lastBody.set(null);
        hits.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody(exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
            int status = exchange.getRequestURI().getPath().contains("denied") ? 401 : 200;
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        ModelHttpProperties properties = new ModelHttpProperties();
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(2000);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000)).build();
        http = new ModelHttpSupport(client, properties);
    }

    /**
     * 关掉临时端口，避免占用后续用例。
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * OPENAI_COMPAT：CHAT→chat/completions，EMBEDDING→embeddings。
     */
    @Test
    void openAiCompatProbesChatAndEmbeddings() {
        OpenAiCompatClient client = new OpenAiCompatClient(http);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ProbeResult chat = client.probe(new ProbeCommand(base, "sk-test", "gpt-4o-mini", ModelCapability.CHAT));
        assertTrue(chat.success());
        assertEquals("/v1/chat/completions", path.get());
        ProbeResult embed = client.probe(new ProbeCommand(base, "sk-test", "text-embedding-3-small", ModelCapability.EMBEDDING));
        assertTrue(embed.success());
        assertEquals("/v1/embeddings", path.get());
        assertEquals("ok", embed.message());
    }

    /**
     * OPENAI_COMPAT：VISION 打 chat/completions，且报文里必须带内联图片块。
     */
    @Test
    void openAiCompatVisionSendsInlineImage() {
        OpenAiCompatClient client = new OpenAiCompatClient(http);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ProbeResult vision = client.probe(new ProbeCommand(base, "sk-test", "gpt-4o", ModelCapability.VISION));
        assertTrue(vision.success());
        assertEquals("/v1/chat/completions", path.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"image_url\""));
        assertTrue(body.contains("data:image/png;base64,"));
        assertTrue(body.contains("你好"));
        assertEquals("视觉连通", vision.message());
    }

    /**
     * OPENAI_COMPAT×RERANK：明确未适配，且不得打 Chat URL。
     */
    @Test
    void openAiCompatUnsupportedDoesNotCallChat() {
        OpenAiCompatClient client = new OpenAiCompatClient(http);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        int before = hits.get();
        ProbeResult rerank = client.probe(new ProbeCommand(base, "sk-test", "rerank-x", ModelCapability.RERANK));
        assertFalse(rerank.success());
        assertTrue(rerank.message().contains("用途未适配"));
        assertTrue(rerank.message().contains("OPENAI_COMPAT×RERANK"));
        assertEquals(before, hits.get());
        assertTrue(path.get() == null || !path.get().contains("chat/completions"));
    }

    /**
     * DashScope×VISION：compatible-mode 基址打兼容 chat/completions；
     * 原生基址打 multimodal-generation（content 用 image + text），不再报未适配。
     */
    @Test
    void dashScopeVisionUsesCompatibleOrNativeMultimodal() {
        DashScopeClient client = new DashScopeClient(http);
        int port = server.getAddress().getPort();
        ProbeResult compatible = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + port + "/compatible-mode/v1", "sk-test", "qwen-vl-max", ModelCapability.VISION));
        assertTrue(compatible.success());
        assertEquals("/compatible-mode/v1/chat/completions", path.get());
        assertTrue(lastBody.get().contains("data:image/png;base64,"));
        assertEquals("视觉连通", compatible.message());

        ProbeResult nativeBase = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + port + "/api/v1", "sk-test", "qwen-vl-max", ModelCapability.VISION));
        assertTrue(nativeBase.success());
        assertEquals("/api/v1/services/aigc/multimodal-generation/generation", path.get());
        assertTrue(lastBody.get().contains("\"image\""));
        assertTrue(lastBody.get().contains("data:image/png;base64,"));
        assertEquals("视觉连通", nativeBase.message());
    }

    /**
     * Anthropic×VISION：走 messages 的 base64 image source，不再报未适配。
     */
    @Test
    void anthropicVisionSendsBase64Image() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ProbeResult anthropic = new AnthropicClient(http)
                .probe(new ProbeCommand(base, "sk-ant", "claude-3", ModelCapability.VISION));
        assertTrue(anthropic.success());
        assertEquals("/v1/messages", path.get());
        assertTrue(lastBody.get().contains("\"type\":\"image\""));
        assertTrue(lastBody.get().contains("\"media_type\":\"image/png\""));
        assertTrue(lastBody.get().contains(VisionProbePayload.PNG_BASE64));
        assertEquals("视觉连通", anthropic.message());
    }

    /**
     * DashScope：兼容 Chat、原生 generation、原生 text-rerank（含 compatible-mode 基址）。
     */
    @Test
    void dashScopeChatEmbedAndRerankPaths() {
        DashScopeClient client = new DashScopeClient(http);
        int port = server.getAddress().getPort();
        ProbeResult compatible = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + port + "/compatible-mode/v1", "sk-test", "qwen-plus", ModelCapability.ROUTER));
        assertTrue(compatible.success());
        assertEquals("/compatible-mode/v1/chat/completions", path.get());

        ProbeResult nativeCall = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + port, "sk-test", "qwen-plus", ModelCapability.CHAT));
        assertTrue(nativeCall.success());
        assertEquals("/api/v1/services/aigc/text-generation/generation", path.get());

        ProbeResult embed = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + port, "sk-test", "text-embedding-v3", ModelCapability.EMBEDDING));
        assertTrue(embed.success());
        assertEquals("/api/v1/services/embeddings/text-embedding/text-embedding", path.get());

        // 即使填了 compatible-mode，Rerank 也必须打原生 text-rerank，禁 Chat
        ProbeResult rerank = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + port + "/compatible-mode/v1", "sk-test", "qwen3-rerank", ModelCapability.RERANK));
        assertTrue(rerank.success());
        assertEquals("/api/v1/services/rerank/text-rerank/text-rerank", path.get());
        assertFalse(path.get().contains("chat"));
    }

    /**
     * Anthropic：CHAT 走 messages；EMBEDDING 未适配且无 HTTP。
     */
    @Test
    void anthropicChatOkEmbeddingUnsupportedNoHttp() {
        AnthropicClient client = new AnthropicClient(http);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ProbeResult chat = client.probe(new ProbeCommand(base, "sk-ant", "claude-3", ModelCapability.CHAT));
        assertTrue(chat.success());
        assertEquals("/v1/messages", path.get());

        int before = hits.get();
        ProbeResult embed = client.probe(new ProbeCommand(base, "sk-ant", "embed-x", ModelCapability.EMBEDDING));
        assertFalse(embed.success());
        assertTrue(embed.message().contains("用途未适配"));
        assertTrue(embed.message().contains("ANTHROPIC×EMBEDDING"));
        assertEquals(before, hits.get());
    }

    /**
     * 401 只返回鉴权失败。上游若回显 Key，也不能出现在 message 里。
     */
    @Test
    void unauthorizedMessageDoesNotLeakKey() {
        OpenAiCompatClient client = new OpenAiCompatClient(http);
        String secret = "sk-super-secret";
        ProbeResult result = client.probe(new ProbeCommand(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/denied", secret, "gpt", ModelCapability.REWRITE));
        assertFalse(result.success());
        assertEquals("鉴权失败", result.message());
        assertFalse(result.message().contains(secret));
    }

    /**
     * nativeRoot：compatible-mode 基址转 /api/v1。
     */
    @Test
    void dashScopeNativeRootStripsCompatibleMode() {
        assertEquals("https://dashscope.aliyuncs.com/api/v1",
                DashScopeNativePaths.apiV1Root("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
                DashScopeTextRerankSupport.url("https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    private String responseBody(String requestPath) {
        if (requestPath.contains("denied")) {
            return "{\"error\":\"bad key sk-super-secret\"}";
        }
        if (requestPath.contains("text-rerank")) {
            return "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}}";
        }
        return "{\"id\":\"ok\"}";
    }
}
