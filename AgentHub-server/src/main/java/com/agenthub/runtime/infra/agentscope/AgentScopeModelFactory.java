package com.agenthub.runtime.infra.agentscope;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.domain.service.ModelTemperaturePolicy;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.model.acl.impl.AdapterEndpointConfig;
import com.agenthub.runtime.model.acl.impl.ModelAdapterFactory;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按模型实例构造 AgentScope 客户端。所属域：runtime 的 infra。
 * 放行规则（重要）：<b>能否试跑 = ModelAdapterFactory 是否注册了该协议</b>，本类不再维护自己的协议白名单。
 * 为什么：白名单一旦与工厂注册表脱节，就会出现「工厂明明支持，试跑却报不支持」的错配（ANTHROPIC 曾如此）。
 * 两条装配路径：
 * <ol>
 *   <li>{@code DASHSCOPE} 原生（baseUrl 不含 compatible-mode）：用 AgentScope 自带的 {@link DashScopeChatModel}，
 *       保留其原生能力，不经 ACL；</li>
 *   <li>其余已注册协议：由 {@link ModelAdapterFactory} 产出 ACL Adapter，再用 {@link AcpModelBridge}
 *       适配成引擎 {@link Model}。AgentScope 没有 Anthropic 实现，必须走这条路。</li>
 * </ol>
 * 明文 Key 只留在本方法栈上交给构造器，不写入日志、会话或事件。
 */
@Component
public class AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeModelFactory.class);

    /** DashScope 走原生接口的判定特征；含该片段时其语义是 OpenAI 兼容协议 */
    private static final String COMPATIBLE_MODE_HINT = "compatible-mode";

    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final SecretStore secretStore;
    private final ModelAdapterFactory adapters;
    private final ObjectMapper json;

    /**
     * @param instances   模型实例
     * @param providers   供应商，决定协议和 baseUrl
     * @param credentials 密文凭证
     * @param secretStore 现有 AES-GCM，主密钥来自 AGENTHUB_SECRET_KEY
     * @param adapters    ACL 适配器工厂，同时充当「协议是否可用」的唯一判据
     * @param json        序列化器，桥接层翻译工具参数时使用
     */
    public AgentScopeModelFactory(ModelInstanceRepository instances, ModelProviderRepository providers,
                                  ModelCredentialRepository credentials, SecretStore secretStore,
                                  ModelAdapterFactory adapters, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.instances = instances;
        this.providers = providers;
        this.credentials = credentials;
        this.secretStore = secretStore;
        this.adapters = adapters;
        this.json = json;
    }

    /**
     * 打开一个对话模型。调用方用完不要把返回值打进日志。
     *
     * @param instanceId 已选定的实例
     * @return AgentScope Model
     * @throws TrialFailure 协议不支持、缺少地址或凭证无法解密
     */
    public Model open(Long instanceId) {
        ModelInstance instance = instances.findById(instanceId)
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .orElseThrow(() -> new TrialFailure("模型实例不可用"));
        ModelProvider provider = providers.findById(instance.getProviderId())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .orElseThrow(() -> new TrialFailure("模型供应商不可用"));
        ModelCredential credential = credentials.findById(instance.getCredentialId())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .orElseThrow(() -> new TrialFailure("模型凭证不可用"));
        String apiKey = decrypt(credential);
        String protocolText = provider.getProtocol() == null ? "" : provider.getProtocol().trim();
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim();
        ModelProtocol protocol = parseProtocol(protocolText);
        try {
            // 灵积原生保留 AgentScope 自带实现：它有原生多模态与增量输出细节，走 ACL 反而丢能力
            if (protocol == ModelProtocol.DASHSCOPE && !baseUrl.contains(COMPATIBLE_MODE_HINT)) {
                return dashScope(apiKey, instance.getModelId(), baseUrl);
            }
            // 其余协议一律问工厂：注册了就放行，没注册（如 CUSTOM）才明确拒绝
            if (!adapters.supports(protocol)) {
                throw new TrialFailure(unsupportedMessage(protocolText));
            }
            if (baseUrl.isBlank()) {
                throw new TrialFailure("供应商缺少 baseUrl");
            }
            return bridge(protocol, apiKey, instance, baseUrl, provider);
        } catch (TrialFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("构造模型客户端失败 instanceId={} type={}", instanceId, ex.getClass().getSimpleName());
            throw new TrialFailure("模型客户端创建失败");
        }
    }

    /**
     * 协议文本 → 枚举。未知文本返回 null，由调用方统一走「不支持」分支。
     *
     * @param protocolText 库里的协议值
     * @return 枚举；未知或空为 null
     */
    private ModelProtocol parseProtocol(String protocolText) {
        if (protocolText.isBlank()) {
            return null;
        }
        try {
            return ModelProtocol.valueOf(protocolText.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 拼「不支持」文案。可用协议集合取自工厂注册表，保证文案与实际能力永不脱节——
     * 这正是旧文案写死「请改用 OPENAI_COMPAT 或 DASHSCOPE」把 Anthropic 排除在外的根因。
     *
     * @param protocolText 库里的协议值
     * @return 已脱敏的业务文案
     */
    private String unsupportedMessage(String protocolText) {
        String available = availableProtocols();
        String name = protocolText.isBlank() ? "（未填写）" : protocolText;
        return "试跑暂不支持协议 " + name + "，当前可用：" + available;
    }

    /**
     * @return 工厂已注册协议的逗号串，用于错误文案与自检
     */
    private String availableProtocols() {
        Set<ModelProtocol> supported = adapters.supportedProtocols();
        if (supported.isEmpty()) {
            return "（无）";
        }
        return supported.stream().map(ModelProtocol::name).collect(Collectors.joining(" / "));
    }

    /**
     * 灵积原生。baseUrl 为空时用 AgentScope 默认域名。
     */
    private Model dashScope(String apiKey, String modelId, String baseUrl) {
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelId)
                .stream(true);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * 通用装配：ACL Adapter + 引擎桥接。三家协议共用这一条路径，不再各写一份分支。
     *
     * @param protocol 已确认由工厂支持的协议
     * @param apiKey   解密后的明文 Key，只用即弃
     * @param instance 模型实例，取其 modelId、用途与扩展配置
     * @param baseUrl  供应商地址
     * @param provider 供应商，用于取 providerName 与 id 作为凭证上下文
     * @return 引擎可用的 Model
     */
    private Model bridge(ModelProtocol protocol, String apiKey, ModelInstance instance, String baseUrl,
                         ModelProvider provider) {
        AdapterEndpointConfig config = AdapterEndpointConfig.builder()
                .providerName(providerName(protocol, provider))
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(instance.getModelId())
                .build();
        // 用途不采样（嵌入 / 重排 / 语音 / 护栏）时出站省略 temperature，与模型中心的固定 0 保持一致
        boolean allowTemperature = samplesTemperature(instance);
        return new AcpModelBridge(adapters.create(protocol, config), instance.getModelId(), json, allowTemperature);
    }

    /**
     * 实例用途是否走采样。用途无法解析时按对话处理（历史脏数据不该把对话链路锁死）。
     *
     * @param instance 模型实例
     * @return true 表示允许把温度带给厂商
     */
    private boolean samplesTemperature(ModelInstance instance) {
        return ModelCapability.tryParse(instance.getCapability())
                .map(ModelTemperaturePolicy::samplesTemperature)
                .orElse(true);
    }

    /**
     * @return 日志用的供应商标识；库里没填时退回协议名小写
     */
    private String providerName(ModelProtocol protocol, ModelProvider provider) {
        String name = provider.getName();
        if (name != null && !name.isBlank()) {
            return name.trim().toLowerCase();
        }
        return protocol.name().toLowerCase();
    }

    /**
     * 解密出站 Key。context 绑供应商 id，与写入时一致；失败不打印密文。
     *
     * @param credential 凭证
     * @return 明文 Key
     * @throws TrialFailure 解密失败
     */
    private String decrypt(ModelCredential credential) {
        try {
            return secretStore.decrypt(credential.getApiKeyCipher(),
                    SecretContexts.modelCredential(credential.getProviderId()));
        } catch (RuntimeException ex) {
            log.warn("模型凭证解密失败 credentialId={} type={}", credential.getId(), ex.getClass().getSimpleName());
            throw new TrialFailure("模型凭证无法解密");
        }
    }
}
