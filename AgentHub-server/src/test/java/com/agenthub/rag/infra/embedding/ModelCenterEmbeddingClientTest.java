package com.agenthub.rag.infra.embedding;

import com.agenthub.model.application.ModelOutboundFactory;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.spi.rag.EmbeddingException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Embedding 批量契约单测。所属域：rag 的 infra。
 * <p>
 * 用本机 JDK HttpServer 充当 MockWebServer（工程 okhttp 多版本并存，禁引 MockWebServer），
 * 禁止打真 8008。覆盖：5→5、1→1、input 必为 string[]、Content-Type charset、条数不符降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelCenterEmbeddingClientTest {

    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelProviderRepository providers;
    @Mock
    private ModelCredentialRepository credentials;
    @Mock
    private SecretStore secretStore;

    private HttpServer server;
    private ModelCenterEmbeddingClient client;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    /**
     * 每个用例起随机端口；默认按「标准 batch」回 N 条（从请求 input 数组长度推断）。
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            hits.incrementAndGet();
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String req = new String(raw, StandardCharsets.UTF_8);
            lastBody.set(req);
            // 空 body 模拟对端「probe 只回 1」：单测里也要能发现客户端没带 input
            int n = countInputs(req);
            String resp = batchOkResponse(n == 0 ? 1 : n, 4);
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        stubModelCenter();
        client = newClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验收主路径：5 条文本一次请求 → 5 条向量同序；input 必须是 string[] 且带 UTF-8 Content-Type。
     */
    @Test
    void embedBatchFiveReturnsFiveInOrder() {
        float[][] vectors = client.embed(9L, List.of("甲", "乙", "丙", "丁", "戊"), 4);

        assertEquals(5, vectors.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(4, vectors[i].length);
            assertEquals((float) i, vectors[i][0], 0.0001f);
        }
        String body = lastBody.get();
        assertFalse(body == null || body.isBlank(), "请求体不得为空（空 body 对端只回 1 条）");
        assertTrue(body.contains("\"input\":[\"甲\",\"乙\",\"丙\",\"丁\",\"戊\"]")
                        || body.contains("\"input\": [\"甲\", \"乙\", \"丙\", \"丁\", \"戊\"]"),
                "input 必须是 string[]，实际=" + body);
        assertTrue(lastContentType.get() != null
                        && lastContentType.get().toLowerCase().contains("application/json")
                        && lastContentType.get().toLowerCase().contains("charset")
                        && lastContentType.get().toLowerCase().contains("utf-8"),
                "Content-Type 须带 charset=UTF-8，实际=" + lastContentType.get());
        assertEquals(1, hits.get());
    }

    /**
     * 单条也发 string[]：1→1，避免与探活「input 为 string」混用导致对端行为分叉。
     */
    @Test
    void embedSingleReturnsOne() {
        float[][] vectors = client.embed(9L, List.of("单条"), 4);

        assertEquals(1, vectors.length);
        assertEquals(4, vectors[0].length);
        assertEquals(0.0f, vectors[0][0], 0.0001f);
        String body = lastBody.get();
        assertTrue(body.contains("\"input\":[\"单条\"]") || body.contains("\"input\": [\"单条\"]"),
                "单条也必须是 string[]，实际=" + body);
        assertEquals(1, hits.get());
    }

    /**
     * parseVectors：5 请求却只给 1 条 data → 必须失败，禁止静默只用 data[0]。
     */
    @Test
    void parseVectorsRejectsCountMismatchFiveToOne() {
        String payload = """
                {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3,0.4]}]}
                """;
        EmbeddingException ex = assertThrows(EmbeddingException.class,
                () -> client.parseVectors(payload, 5, 4));
        assertTrue(ex.getMessage().contains("条数"));
        assertTrue(ex.getMessage().contains("1"));
        assertTrue(ex.getMessage().contains("5"));
    }

    /**
     * 缺 index 时按下标收齐，仍须 N→N。
     */
    @Test
    void parseVectorsUsesOrdinalWhenIndexMissing() {
        String payload = """
                {"data":[
                  {"embedding":[0.1,0.2,0.3,0.4]},
                  {"embedding":[1.1,1.2,1.3,1.4]},
                  {"embedding":[2.1,2.2,2.3,2.4]}
                ]}
                """;
        float[][] vectors = client.parseVectors(payload, 3, 4);
        assertEquals(3, vectors.length);
        assertEquals(0.1f, vectors[0][0], 0.0001f);
        assertEquals(2.1f, vectors[2][0], 0.0001f);
    }

    /**
     * 模拟「文档声称支持 batch、实际只回 1 条」：客户端应降级逐条，最终仍 5→5。
     */
    @Test
    void embedFallsBackToOneByOneWhenBatchReturnsSingle() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            hits.incrementAndGet();
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String req = new String(raw, StandardCharsets.UTF_8);
            lastBody.set(req);
            // 无论请求几条都只回 1 条：复现「1 与 5 不一致」；逐条时请求本身就是 1，仍合法
            String resp = batchOkResponse(1, 4);
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        stubModelCenter();
        hits.set(0);
        client = newClient();

        float[][] vectors = client.embed(9L, List.of("a", "b", "c", "d", "e"), 4);

        assertEquals(5, vectors.length);
        // 1 次失败 batch + 5 次逐条
        assertEquals(6, hits.get());
        assertFalse(lastBody.get() == null || lastBody.get().isBlank());
    }

    /**
     * 测试用客户端：与生产一致强制 HTTP/1.1，避免单测环境与真机行为分叉。
     */
    private ModelCenterEmbeddingClient newClient() {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(1000))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new ModelCenterEmbeddingClient(instances, providers, credentials, secretStore,
                new ModelOutboundFactory(), http, 2000);
    }

    private void stubModelCenter() {
        int port = server.getAddress().getPort();
        ModelInstance instance = new ModelInstance();
        instance.setId(9L);
        instance.setProviderId(1L);
        instance.setCredentialId(2L);
        instance.setModelId("bge-base-zh-v1.5");
        when(instances.findById(9L)).thenReturn(Optional.of(instance));

        ModelProvider provider = new ModelProvider();
        provider.setId(1L);
        provider.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        provider.setProtocol("OPENAI_COMPAT");
        when(providers.findById(1L)).thenReturn(Optional.of(provider));

        ModelCredential credential = new ModelCredential();
        credential.setId(2L);
        credential.setApiKeyCipher("cipher");
        when(credentials.findById(2L)).thenReturn(Optional.of(credential));
        when(secretStore.decrypt(anyString(), any())).thenReturn("sk-test");
    }

    /**
     * 从请求 JSON 粗算 input 数组长度；单测够用，不引入完整 JSON 解析依赖路径。
     */
    private static int countInputs(String req) {
        if (req == null || req.isBlank()) {
            return 0;
        }
        int start = req.indexOf("\"input\":");
        if (start < 0) {
            return 0;
        }
        int bracket = req.indexOf('[', start);
        if (bracket < 0) {
            // 单 string 形式
            return 1;
        }
        int end = req.indexOf(']', bracket);
        if (end < 0) {
            return 0;
        }
        String inside = req.substring(bracket + 1, end).trim();
        if (inside.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < inside.length(); i++) {
            if (inside.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }

    /**
     * 构造 OpenAI 兼容 data[]；第 0 维写入 index，便于断言同序。
     */
    private static String batchOkResponse(int count, int dim) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
            sb.append((float) i);
            for (int d = 1; d < dim; d++) {
                sb.append(',').append(0.01f * d);
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
