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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Embedding 出站矩阵关键格：DashScope 原生 text-embedding 与 Anthropic 未适配。
 * 本文件只在本机执行（src/test 不入库），全部打本机 mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelCenterEmbeddingNativeTest {

    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelProviderRepository providers;
    @Mock
    private ModelCredentialRepository credentials;
    @Mock
    private SecretStore secretStore;

    @Test
    void dashScopeNativeEmbeddingUsesTextEmbeddingPathAndTextIndex() throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/embeddings/text-embedding/text-embedding", exchange -> {
            hits.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"output\":{\"embeddings\":["
                    + "{\"text_index\":1,\"embedding\":[0.5,0.6]},"
                    + "{\"text_index\":0,\"embedding\":[0.1,0.2]}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            stubModel(server, "DASHSCOPE", "/api/v1", "text-embedding-v4");
            ModelCenterEmbeddingClient client = newClient();
            float[][] vectors = client.embed(9L, List.of("a", "b"), 2);
            assertEquals(1, hits.get());
            assertEquals(2, vectors.length);
            // text_index 必须归位：请求第 0 条是 [0.1,0.2]
            assertEquals(0.1f, vectors[0][0], 0.0001);
            assertEquals(0.5f, vectors[1][0], 0.0001);
            assertTrue(lastBody.get().contains("\"texts\""), lastBody.get());
            assertTrue(lastBody.get().contains("\"text-embedding-v4\""), lastBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicEmbeddingIsRejectedBeforeAnyHttp() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            stubModel(server, "ANTHROPIC", "/v1", "claude-sonnet-4-5");
            ModelCenterEmbeddingClient client = newClient();
            EmbeddingException ex = assertThrows(EmbeddingException.class,
                    () -> client.embed(9L, List.of("a"), 2));
            assertTrue(ex.getMessage().contains("该协议不支持此用途"), ex.getMessage());
            assertEquals(0, hits.get(), "未适配协议不得发出 HTTP");
        } finally {
            server.stop(0);
        }
    }

    private ModelCenterEmbeddingClient newClient() {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofMillis(1000))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new ModelCenterEmbeddingClient(instances, providers, credentials, secretStore,
                new ModelOutboundFactory(), http, 2000);
    }

    private void stubModel(HttpServer server, String protocol, String suffix, String modelId) {
        int port = server.getAddress().getPort();
        ModelInstance instance = new ModelInstance();
        instance.setId(9L);
        instance.setProviderId(1L);
        instance.setCredentialId(2L);
        instance.setModelId(modelId);
        when(instances.findById(9L)).thenReturn(Optional.of(instance));

        ModelProvider provider = new ModelProvider();
        provider.setId(1L);
        provider.setProtocol(protocol);
        provider.setBaseUrl("http://127.0.0.1:" + port + suffix);
        when(providers.findById(1L)).thenReturn(Optional.of(provider));

        ModelCredential credential = new ModelCredential();
        credential.setId(2L);
        credential.setApiKeyCipher("cipher");
        when(credentials.findById(2L)).thenReturn(Optional.of(credential));
        when(secretStore.decrypt(anyString(), any())).thenReturn("sk-test");
    }
}
