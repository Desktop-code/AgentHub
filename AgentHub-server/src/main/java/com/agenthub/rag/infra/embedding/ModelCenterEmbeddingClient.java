package com.agenthub.rag.infra.embedding;

import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.EmbeddingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型中心驱动的向量化客户端。所属域：rag 的 infra。
 * <p>
 * 调用链：KB.embedding_model_id → model_instance → model_provider（base_url）+ model_credential（密文）→
 * {@code POST {baseUrl}/embeddings}（OpenAI 兼容协议）。Key 出站前才解密，只留在本方法内存里，禁止进日志。
 * <p>
 * 批量契约（硬约束）：
 * <ul>
 *   <li>请求 {@code input} 必须是 {@code string[]}，禁止把多条拼成单个 string；</li>
 *   <li>响应必须收齐 {@code data[]} 全部元素（按 index 或数组序归位），禁止只取 {@code data[0]}；</li>
 *   <li>返回长度必须等于请求条数；维度必须等于 expectedDimension，不截断、不补零。</li>
 * </ul>
 * HTTP 出站硬约束（针对本机 8008 / Uvicorn 一类 HTTP/1.1-only 服务）：
 * <ul>
 *   <li>强制 {@link HttpClient.Version#HTTP_1_1}：JDK 默认偏好 HTTP/2 会发 h2c Upgrade，
 *       对端丢 body 后表现为「空 body 只回 1 条」，同连接再 POST 则 {@code 400 Invalid HTTP request}；</li>
 *   <li>{@code Content-Type: application/json; charset=UTF-8}，body 用已知长度字节发布（带 Content-Length，禁 chunked+Upgrade）；</li>
 *   <li>禁止跟随重定向，避免 POST 变 GET 丢 body。</li>
 * </ul>
 * 部分本地 OpenAI 兼容服务对数组 input 仍只回 1 条；此时降级为逐条请求再拼回，保证入库 N→N。
 * <p>
 * 测试替身：单元测试用本机 mock HttpServer / 直接调 {@link #parseVectors}，不打真实 8008
 * （工程 okhttp 多版本并存，不引 MockWebServer 以免冲突）。
 */
@Component
public class ModelCenterEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(ModelCenterEmbeddingClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** OpenAI 兼容向量化接口路径 */
    private static final String EMBEDDINGS_PATH = "/embeddings";
    /** 必须带 charset：部分本地网关对缺省 charset 的 JSON 按非 UTF-8 解析会 400 */
    private static final String JSON_UTF8 = "application/json; charset=UTF-8";

    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final SecretStore secretStore;
    private final HttpClient httpClient;
    private final int readTimeoutMs;

    /**
     * Spring 注入入口。必须标 {@link Autowired}：另有包内测试构造器时，否则容器会报无默认构造。
     *
     * @param instances     模型实例
     * @param providers     供应商（取 baseUrl / 协议）
     * @param credentials   凭证（取密文）
     * @param secretStore   解密
     * @param readTimeoutMs 单次出站上限；向量化比 chat 慢，默认放宽
     */
    @Autowired
    public ModelCenterEmbeddingClient(ModelInstanceRepository instances, ModelProviderRepository providers,
                                      ModelCredentialRepository credentials, SecretStore secretStore,
                                      @Value("${agenthub.rag.embedding.read-timeout-ms:30000}") int readTimeoutMs) {
        this(instances, providers, credentials, secretStore,
                // 本地 Embedding（Uvicorn/vLLM 等）只认 HTTP/1.1；默认 HTTP/2 h2c 会丢 body → 条数 1 与 N 不一致 / 400
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                readTimeoutMs);
    }

    /**
     * 测试注入用：可换本地 HttpServer 对应的 HttpClient，避免打真 8008。
     * <p>
     * 非 Spring 构造，禁止加 {@code @Autowired}，否则会去找容器里的 {@link HttpClient} Bean。
     *
     * @param httpClient    出站客户端
     * @param readTimeoutMs 读超时
     */
    ModelCenterEmbeddingClient(ModelInstanceRepository instances, ModelProviderRepository providers,
                               ModelCredentialRepository credentials, SecretStore secretStore,
                               HttpClient httpClient, int readTimeoutMs) {
        this.instances = instances;
        this.providers = providers;
        this.credentials = credentials;
        this.secretStore = secretStore;
        this.httpClient = httpClient;
        this.readTimeoutMs = Math.max(readTimeoutMs, 1000);
    }

    /**
     * 批量向量化：优先一次 POST string[]；若对端只回部分条数则逐条降级拼回。
     *
     * @param modelInstanceId   模型实例 id
     * @param texts             待向量化文本
     * @param expectedDimension 期望维度；>0 时强制校验
     * @return 与 texts 等长、顺序一致的向量
     * @throws EmbeddingException 配置缺失、出站失败、维度不符、逐条仍失败
     */
    @Override
    public float[][] embed(Long modelInstanceId, List<String> texts, int expectedDimension) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        // 拷成独立 ArrayList：避免 subList 视图在部分序列化器上表现异常，并保证 JSON 一定是 string[]
        List<String> batch = new ArrayList<>(texts);
        ModelInstance instance = requireInstance(modelInstanceId);
        ModelProvider provider = requireProvider(instance);
        String apiKey = decryptKey(instance);
        String url = join(provider.getBaseUrl(), EMBEDDINGS_PATH);

        try {
            return requestEmbeddings(url, apiKey, instance.getModelId(), batch, expectedDimension);
        } catch (EmbeddingException ex) {
            // 仅「条数不一致」且 N>1 时降级：其它错误（鉴权/维度/空响应）不应掩盖
            if (batch.size() <= 1 || !isCountMismatch(ex)) {
                throw ex;
            }
            log.warn("[RAG] 批量向量化条数不符，降级逐条 model={} size={} reason={}",
                    instance.getModelId(), batch.size(), ex.getMessage());
            return embedOneByOne(url, apiKey, instance.getModelId(), batch, expectedDimension);
        }
    }

    /**
     * @return 批量上限；多数厂商单次上限 2048，这里取保守值便于控制超时
     */
    @Override
    public int maxBatchSize() {
        return 16;
    }

    /**
     * 一次 HTTP：input 固定为 string[]，解析时收齐 data[]。
     * <p>
     * body 先编成 UTF-8 字节再 {@code ofByteArray}：强制带 Content-Length，避免 h2c/chunked 路径下对端读到空 body。
     */
    private float[][] requestEmbeddings(String url, String apiKey, String modelId,
                                        List<String> texts, int expectedDimension) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        // 显式 String[]：Jackson 一定写出 JSON 数组；禁止 List 在个别配置下被当成单值
        body.put("input", texts.toArray(new String[0]));

        byte[] payload = toJsonBytes(body);
        if (payload.length == 0) {
            // 空 body 对端常当 probe 只回 1 条，必须在出站前拦住
            throw new EmbeddingException("向量化请求体为空，拒绝出站");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", JSON_UTF8)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("向量化被中断", e);
        } catch (Exception e) {
            // 只报类型与短说明，不拼 URL 全串，避免把查询串里的敏感参数带进 error_msg
            throw new EmbeddingException("向量化请求失败：" + e.getClass().getSimpleName(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new EmbeddingException("向量化失败：HTTP " + status + " " + brief(response.body(), apiKey));
        }
        return parseVectors(response.body(), texts.size(), expectedDimension);
    }

    /**
     * 逐条向量化再按原序拼回。用于本地服务对 batch input 只回 1 条的兼容。
     */
    private float[][] embedOneByOne(String url, String apiKey, String modelId,
                                    List<String> texts, int expectedDimension) {
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            float[][] one = requestEmbeddings(url, apiKey, modelId, List.of(texts.get(i)), expectedDimension);
            if (one.length != 1) {
                throw new EmbeddingException("向量化返回条数 " + one.length + " 与请求 1 不一致");
            }
            out[i] = one[0];
        }
        return out;
    }

    /**
     * 解析 OpenAI 兼容响应：{@code data[].embedding}，按 index（缺省则按下标）归位。
     * <p>
     * 禁止只取 {@code data[0]} 当作整批结果；条数必须与请求一致，否则抛错给上层决定是否降级。
     *
     * @param payload           响应 JSON
     * @param expectedCount     请求文本条数
     * @param expectedDimension 期望维；≤0 时不校验维
     * @return 与请求等长的向量
     */
    float[][] parseVectors(String payload, int expectedCount, int expectedDimension) {
        if (payload == null || payload.isBlank()) {
            throw new EmbeddingException("向量化返回空响应");
        }
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (Exception e) {
            throw new EmbeddingException("向量化响应不是合法 JSON", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new EmbeddingException("向量化响应缺少 data 数组");
        }
        if (data.size() != expectedCount) {
            throw new EmbeddingException("向量化返回条数 " + data.size() + " 与请求 " + expectedCount + " 不一致");
        }
        // 按 index 归位：厂商不保证返回顺序；缺 index 时退回数组下标，仍须收齐每一条
        float[][] out = new float[expectedCount][];
        int ordinal = 0;
        for (JsonNode item : data) {
            int index = resolveIndex(item, ordinal, expectedCount);
            float[] vector = readEmbedding(item.path("embedding"), expectedDimension);
            if (out[index] != null) {
                throw new EmbeddingException("向量化返回重复 index：" + index);
            }
            out[index] = vector;
            ordinal++;
        }
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                throw new EmbeddingException("向量化返回缺少第 " + i + " 条结果");
            }
        }
        return out;
    }

    /**
     * 优先用响应里的 index；缺失或非法时用数组序，避免「有 data 却因无 index 全丢」。
     */
    private static int resolveIndex(JsonNode item, int ordinal, int expectedCount) {
        JsonNode indexNode = item.get("index");
        if (indexNode == null || indexNode.isNull() || !indexNode.isNumber()) {
            return ordinal;
        }
        int index = indexNode.asInt();
        if (index < 0 || index >= expectedCount) {
            throw new EmbeddingException("向量化返回的 index 越界：" + index);
        }
        return index;
    }

    /**
     * 读取单条 embedding 浮点数组；维度不符立刻失败。
     */
    private static float[] readEmbedding(JsonNode embedding, int expectedDimension) {
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new EmbeddingException("向量化返回缺少 embedding 数组");
        }
        // 防止把「多条向量误塞进一条 embedding」当成一条通过
        if (embedding.get(0).isArray()) {
            throw new EmbeddingException("向量化 embedding 出现嵌套数组，期望一维 float[]");
        }
        if (expectedDimension > 0 && embedding.size() != expectedDimension) {
            throw new EmbeddingException("向量维度 " + embedding.size()
                    + " 与知识库维度 " + expectedDimension + " 不一致，请检查 Embedding 模型的维度配置");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    private static boolean isCountMismatch(EmbeddingException ex) {
        String msg = ex.getMessage();
        return msg != null && msg.contains("条数") && msg.contains("不一致");
    }

    private ModelInstance requireInstance(Long id) {
        if (id == null) {
            throw new EmbeddingException("知识库未配置 Embedding 模型");
        }
        return instances.findById(id)
                .orElseThrow(() -> new EmbeddingException("Embedding 模型实例不存在或已删除"));
    }

    private ModelProvider requireProvider(ModelInstance instance) {
        ModelProvider provider = providers.findById(instance.getProviderId())
                .orElseThrow(() -> new EmbeddingException("Embedding 模型的供应商不存在"));
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new EmbeddingException("供应商未配置 baseUrl，无法向量化");
        }
        return provider;
    }

    /**
     * 解密凭证。密文缺失或解密失败都收敛成不含密文的业务异常。
     */
    private String decryptKey(ModelInstance instance) {
        var credential = credentials.findById(instance.getCredentialId())
                .orElseThrow(() -> new EmbeddingException("Embedding 模型的凭证不存在"));
        String cipher = credential.getApiKeyCipher();
        if (cipher == null || cipher.isBlank()) {
            throw new EmbeddingException("Embedding 模型的凭证未配置 Key");
        }
        try {
            return secretStore.decrypt(cipher, SecretContexts.modelCredential(instance.getProviderId()));
        } catch (RuntimeException e) {
            // 不把 e.getMessage() 直接抛给前端：里面可能含密文片段
            log.warn("[RAG] 解密 Embedding 凭证失败 instanceId={}", instance.getId());
            throw new EmbeddingException("Embedding 凭证解密失败，请到模型管理重置 Key");
        }
    }

    private static String join(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * 序列化为 UTF-8 字节；保证 Content-Length 可知、编码固定。
     */
    private static byte[] toJsonBytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new EmbeddingException("向量化报文无法序列化", e);
        }
    }

    /**
     * 失败说明截断并抹掉 Key，只用于 error_msg 与日志。
     */
    private static String brief(String body, String apiKey) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (apiKey != null && !apiKey.isBlank()) {
            text = text.replace(apiKey, "***");
        }
        return text.length() > 180 ? text.substring(0, 180) : text;
    }
}
