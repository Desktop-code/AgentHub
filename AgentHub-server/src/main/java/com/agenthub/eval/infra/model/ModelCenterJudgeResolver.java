package com.agenthub.eval.infra.model;

import com.agenthub.eval.domain.model.OpenJudgeJudge;
import com.agenthub.eval.domain.service.JudgeModelResolver;
import com.agenthub.eval.domain.service.JudgeModelUnavailableException;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 由模型中心解出打分模型凭据。所属域：eval 的 infra（读 model 域只读仓储）。
 * <p>复用现网唯一一套密钥能力：{@link SecretStore}（AES-GCM，主密钥
 * {@code AGENTHUB_SECRET_KEY}）+ {@link SecretContexts#modelCredential(Long)} 绑定串，
 * <b>不新建密钥库，也不缓存明文 Key</b>。
 * <p>密文只在本次调用内解开并交给 {@link OpenJudgeJudge}；日志只打 instanceId 与供应商 id。
 */
@Slf4j
@Component
public class ModelCenterJudgeResolver implements JudgeModelResolver {

    /** DashScope 的 OpenAI 兼容前缀；py-openjudge 只认 OpenAI 兼容协议 */
    private static final String COMPATIBLE_MODE = "compatible-mode";

    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final SecretStore secretStore;

    /**
     * @param instances   模型实例
     * @param providers   供应商（决定 baseUrl 与协议）
     * @param credentials 凭证密文
     * @param secretStore 现有 AES-GCM 解密
     */
    public ModelCenterJudgeResolver(ModelInstanceRepository instances, ModelProviderRepository providers,
                                    ModelCredentialRepository credentials, SecretStore secretStore) {
        this.instances = instances;
        this.providers = providers;
        this.credentials = credentials;
        this.secretStore = secretStore;
    }

    /** {@inheritDoc} */
    @Override
    public OpenJudgeJudge resolve(Long instanceId) {
        if (instanceId == null) {
            throw new JudgeModelUnavailableException("未选择打分模型");
        }
        ModelInstance instance = instances.findById(instanceId)
                .orElseThrow(() -> new JudgeModelUnavailableException("打分模型不存在或已删除：" + instanceId));
        if (!Integer.valueOf(1).equals(instance.getStatus())) {
            throw new JudgeModelUnavailableException("打分模型已停用：" + instanceId);
        }
        String model = instance.getModelId() == null ? "" : instance.getModelId().trim();
        if (model.isEmpty()) {
            throw new JudgeModelUnavailableException("打分模型未配置 modelId：" + instanceId);
        }

        ModelProvider provider = providers.findById(instance.getProviderId())
                .orElseThrow(() -> new JudgeModelUnavailableException("打分模型的供应商不存在：" + instanceId));
        String baseUrl = openAiCompatibleBaseUrl(provider, instanceId);
        String apiKey = decryptApiKey(instance);

        log.debug("打分模型凭据已就绪 instanceId={} providerId={} baseUrl={}",
                instanceId, provider.getId(), baseUrl);
        return new OpenJudgeJudge(model, baseUrl, apiKey);
    }

    /**
     * 解出明文 Key。
     * <p>失败只抛「凭据不可用」，<b>不</b>回落到全局 env Key——那会让用户以为用的是所选实例。
     *
     * @param instance 打分模型实例
     * @return 明文 Key
     * @throws JudgeModelUnavailableException 缺凭证 / 密文为空 / 解密失败
     */
    private String decryptApiKey(ModelInstance instance) {
        ModelCredential credential = credentials.findById(instance.getCredentialId())
                .orElseThrow(() -> new JudgeModelUnavailableException(
                        "打分模型的凭证不存在：" + instance.getId()));
        if (!Integer.valueOf(1).equals(credential.getStatus())) {
            throw new JudgeModelUnavailableException("打分模型的凭证已停用：" + instance.getId());
        }
        String cipher = credential.getApiKeyCipher();
        if (cipher == null || cipher.isBlank()) {
            throw new JudgeModelUnavailableException("打分模型的凭证未配置 Key：" + instance.getId());
        }
        try {
            // AAD 绑定供应商：密文被搬走就解不开，因此必须用凭证自己的 providerId
            String plain = secretStore.decrypt(cipher, SecretContexts.modelCredential(credential.getProviderId()));
            if (plain == null || plain.isBlank()) {
                throw new JudgeModelUnavailableException("打分模型的凭证解密结果为空：" + instance.getId());
            }
            return plain;
        } catch (JudgeModelUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 只打类型，不把异常 message（可能含密文片段）带进日志
            log.warn("解密打分模型凭证失败 instanceId={} type={}",
                    instance.getId(), ex.getClass().getSimpleName());
            throw new JudgeModelUnavailableException(
                    "打分模型凭据解密失败，请到模型管理重置 Key：" + instance.getId());
        }
    }

    /**
     * 把供应商基址归一成 py-openjudge 能用的 OpenAI 兼容基址。
     * <p>Python 侧用 {@code AsyncOpenAI}，只认 OpenAI 协议：
     * <ul>
     *   <li>已含 {@code compatible-mode} → 原样用；</li>
     *   <li>DashScope 原生基址（{@code .../api/v1}）→ 换成同主机
     *       {@code .../compatible-mode/v1}，否则评分必然 404；</li>
     *   <li>其它（OpenAI 兼容中转 / 自建网关）→ 原样用；</li>
     *   <li>空 → null，交给 sidecar 环境变量 {@code OPENAI_BASE_URL}。</li>
     * </ul>
     *
     * @param provider   供应商
     * @param instanceId 仅用于错误文案
     * @return 兼容基址，可空
     * @throws JudgeModelUnavailableException 协议为 Anthropic（本阶段不支持）
     */
    private String openAiCompatibleBaseUrl(ModelProvider provider, Long instanceId) {
        String protocol = provider.getProtocol() == null ? "" : provider.getProtocol().trim().toUpperCase();
        if ("ANTHROPIC".equals(protocol)) {
            throw new JudgeModelUnavailableException(
                    "打分模型协议不支持：Anthropic 非 OpenAI 兼容，无法用于 OpenJudge（实例 " + instanceId + "）");
        }
        String raw = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim();
        if (raw.isEmpty()) {
            return null;
        }
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.contains(COMPATIBLE_MODE)) {
            return raw;
        }
        if ("DASHSCOPE".equals(protocol)) {
            // 原生基址形如 https://dashscope.aliyuncs.com/api/v1：剥到主机再挂兼容前缀
            int index = raw.indexOf("/api/v1");
            String host = index > 0 ? raw.substring(0, index) : raw;
            return host + "/compatible-mode/v1";
        }
        return raw;
    }
}
