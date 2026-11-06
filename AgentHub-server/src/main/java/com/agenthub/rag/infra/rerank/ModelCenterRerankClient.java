package com.agenthub.rag.infra.rerank;

import com.agenthub.common.text.SecretBrief;
import com.agenthub.model.application.ModelOutboundFactory;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.domain.service.ModelCredentialResolver;
import com.agenthub.model.domain.service.PurposeIoKind;
import com.agenthub.model.domain.service.PurposeRoute;
import com.agenthub.model.infra.adapter.DashScopeTextRerankSupport;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankException;
import com.agenthub.spi.rag.RerankHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型中心驱动的重排客户端。所属域：rag 的 infra。
 * <p>
 * 经 {@link ModelOutboundFactory}（同连通测矩阵）选路：当前仅 {@code DASHSCOPE×RERANK}
 * 真打 text-rerank；其余格抛「用途未适配」，禁止改打 Chat。
 */
@Component
public class ModelCenterRerankClient implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(ModelCenterRerankClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final SecretStore secretStore;
    private final ModelOutboundFactory outbound;
    private final HttpClient httpClient;
    private final int readTimeoutMs;

    /**
     * Spring 入口。
     *
     * @param instances     实例
     * @param providers     供应商
     * @param credentials   凭证
     * @param secretStore   解密
     * @param outbound      协议×用途总闸
     * @param readTimeoutMs 读超时
     */
    @Autowired
    public ModelCenterRerankClient(ModelInstanceRepository instances, ModelProviderRepository providers,
                                   ModelCredentialRepository credentials, SecretStore secretStore,
                                   ModelOutboundFactory outbound,
                                   @Value("${agenthub.rag.rerank.read-timeout-ms:30000}") int readTimeoutMs) {
        this(instances, providers, credentials, secretStore, outbound,
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                readTimeoutMs);
    }

    /**
     * 测试注入：可换本地 HttpServer 对应的 HttpClient。
     *
     * @param httpClient    出站客户端
     * @param readTimeoutMs 读超时
     */
    ModelCenterRerankClient(ModelInstanceRepository instances, ModelProviderRepository providers,
                            ModelCredentialRepository credentials, SecretStore secretStore,
                            ModelOutboundFactory outbound, HttpClient httpClient, int readTimeoutMs) {
        this.instances = instances;
        this.providers = providers;
        this.credentials = credentials;
        this.secretStore = secretStore;
        this.outbound = outbound;
        this.httpClient = httpClient;
        this.readTimeoutMs = Math.max(readTimeoutMs, 1000);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RerankHit> rerank(Long modelInstanceId, String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        ModelInstance instance = requireInstance(modelInstanceId);
        ModelCapability capability = parseCapability(instance);
        ModelProvider provider = requireProvider(instance);
        ModelProtocol protocol = parseProtocol(provider);
        // 总闸：未适配立即失败，且下方不会拼 Chat URL
        PurposeRoute route = outbound.resolve(protocol, capability);
        if (!route.adapted() || route.kind() != PurposeIoKind.RERANK) {
            throw new RerankException(route.message());
        }
        if (protocol != ModelProtocol.DASHSCOPE) {
            throw new RerankException(route.message());
        }
        String apiKey = decryptKey(instance);
        String url = DashScopeTextRerankSupport.url(provider.getBaseUrl());
        Map<String, Object> body = DashScopeTextRerankSupport.requestMap(
                instance.getModelId(), query, documents, topN);
        return postAndParse(url, apiKey, body);
    }

    private List<RerankHit> postAndParse(String url, String apiKey, Map<String, Object> body) {
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw new RerankException("重排报文无法序列化", ex);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new RerankException("重排失败：HTTP " + status + " " + brief(response.body(), apiKey));
            }
            return DashScopeTextRerankSupport.parseResults(response.body());
        } catch (RerankException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RerankException("重排被中断", ex);
        } catch (Exception ex) {
            throw new RerankException("重排请求失败：" + ex.getClass().getSimpleName(), ex);
        }
    }

    private ModelInstance requireInstance(Long id) {
        if (id == null) {
            throw new RerankException("未配置 Rerank 模型");
        }
        return instances.findById(id)
                .orElseThrow(() -> new RerankException("Rerank 模型实例不存在或已删除"));
    }

    private ModelCapability parseCapability(ModelInstance instance) {
        try {
            return ModelCapability.parse(instance.getCapability());
        } catch (IllegalArgumentException ex) {
            throw new RerankException("Rerank 模型用途非法：" + instance.getCapability());
        }
    }

    private ModelProvider requireProvider(ModelInstance instance) {
        ModelProvider provider = providers.findById(instance.getProviderId())
                .orElseThrow(() -> new RerankException("Rerank 模型的供应商不存在"));
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new RerankException("供应商未配置 baseUrl，无法重排");
        }
        return provider;
    }

    private ModelProtocol parseProtocol(ModelProvider provider) {
        try {
            return ModelProtocol.valueOf(provider.getProtocol().trim().toUpperCase());
        } catch (Exception ex) {
            throw new RerankException("供应商协议非法：" + provider.getProtocol());
        }
    }

    private String decryptKey(ModelInstance instance) {
        var credential = credentials.findById(instance.getCredentialId())
                .orElseThrow(() -> new RerankException("Rerank 模型的凭证不存在"));
        String cipher = credential.getApiKeyCipher();
        if (cipher == null || cipher.isBlank()) {
            throw new RerankException("Rerank 模型的凭证未配置 Key");
        }
        try {
            return ModelCredentialResolver.decryptKey(secretStore, cipher, instance.getProviderId());
        } catch (RuntimeException e) {
            log.warn("[RAG] 解密 Rerank 凭证失败 instanceId={}", instance.getId());
            throw new RerankException("Rerank 凭证解密失败，请到模型管理重置 Key");
        }
    }

    private static String brief(String body, String apiKey) {
        // 折行 + 抹本次 Key + 截 180，实现见 common 的 SecretBrief（原先三处逐字复制）
        return SecretBrief.brief(body, apiKey);
    }
}
