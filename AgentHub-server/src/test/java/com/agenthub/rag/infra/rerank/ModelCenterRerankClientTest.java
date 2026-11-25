package com.agenthub.rag.infra.rerank;

import com.agenthub.model.application.ModelOutboundFactory;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.infra.adapter.DashScopeTextRerankSupport;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.spi.rag.RerankException;
import com.agenthub.spi.rag.RerankHit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Rerank 出站：DASHSCOPE 真路径 mock 200；OPENAI 未适配且无 Chat URL。
 */
@ExtendWith(MockitoExtension.class)
class ModelCenterRerankClientTest {

    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelProviderRepository providers;
    @Mock
    private ModelCredentialRepository credentials;
    @Mock
    private SecretStore secretStore;

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private ModelCenterRerankClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.95},{\"index\":0,\"relevance_score\":0.1}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        client = new ModelCenterRerankClient(instances, providers, credentials, secretStore,
                new ModelOutboundFactory(), http, 2000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void dashScopeRerankHitsTextRerankPath() {
        stubInstance("DASHSCOPE", "RERANK", "qwen3-rerank",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1");
        List<RerankHit> hits = client.rerank(9L, "退货", List.of("无关", "退货政策说明"), 2);
        assertEquals("/api/v1/services/rerank/text-rerank/text-rerank", path.get());
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).index());
        assertEquals(0.95d, hits.get(0).score(), 1e-6);
    }

    @Test
    void openAiCompatRerankUnsupportedWithoutHttp() {
        stubInstance("OPENAI_COMPAT", "RERANK", "rerank-x",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        path.set(null);
        RerankException ex = assertThrows(RerankException.class,
                () -> client.rerank(9L, "q", List.of("a", "b"), 2));
        assertTrue(ex.getMessage().contains("用途未适配"));
        assertTrue(ex.getMessage().contains("OPENAI_COMPAT×RERANK"));
        assertTrue(path.get() == null || !path.get().contains("chat"));
    }

    @Test
    void parseResultsSortsByScore() {
        List<RerankHit> hits = DashScopeTextRerankSupport.parseResults(
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.2},{\"index\":2,\"relevance_score\":0.9}]}}");
        assertEquals(2, hits.get(0).index());
        assertEquals(0, hits.get(1).index());
    }

    private void stubInstance(String protocol, String capability, String modelId, String baseUrl) {
        ModelInstance instance = new ModelInstance();
        instance.setId(9L);
        instance.setProviderId(1L);
        instance.setCredentialId(2L);
        instance.setCapability(capability);
        instance.setModelId(modelId);
        when(instances.findById(9L)).thenReturn(Optional.of(instance));

        ModelProvider provider = new ModelProvider();
        provider.setId(1L);
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        when(providers.findById(1L)).thenReturn(Optional.of(provider));

        ModelCredential credential = new ModelCredential();
        credential.setId(2L);
        credential.setProviderId(1L);
        credential.setApiKeyCipher("cipher");
        lenient().when(credentials.findById(2L)).thenReturn(Optional.of(credential));
        lenient().when(secretStore.decrypt(anyString(), any())).thenReturn("sk-test");
    }
}
