package com.agenthub.runtime.infra.agentscope;

import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.model.acl.impl.ModelAdapterFactory;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 试跑模型装配的协议闸门单测。所属域：runtime 的 infra。
 * 核心断言：<b>ANTHROPIC 不再抛「暂不支持协议」</b>，而是与 OPENAI_COMPAT 一样装配出可用 Model；
 * 未注册协议（CUSTOM）仍明确拒绝，且错误文案列出真实可用协议（不再写死两项把 Anthropic 排除）。
 * 约束：Mock 仓储 + 假 Key，零外网；只验证装配阶段，不发任何请求。
 */
@ExtendWith(MockitoExtension.class)
class AgentScopeModelFactoryProtocolTest {

    private static final long PROVIDER_ID = 9L;
    private static final long INSTANCE_ID = 21L;
    private static final long CREDENTIAL_ID = 5L;
    private static final String FAKE_KEY = "sk-test-not-real";

    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelProviderRepository providers;
    @Mock
    private ModelCredentialRepository credentials;
    @Mock
    private SecretStore secretStore;

    private AgentScopeModelFactory factory;

    /**
     * 每个用例重建工厂：装配逻辑无内部状态，重建只是为了隔离 Mock 交互。
     */
    @BeforeEach
    void setUp() {
        factory = new AgentScopeModelFactory(instances, providers, credentials, secretStore,
                new ModelAdapterFactory(new ObjectMapper()), new ObjectMapper());
        when(secretStore.decrypt(any(), any())).thenReturn(FAKE_KEY);
    }

    /**
     * 本次要修的主场景：绑定 Anthropic 供应商的实例必须能装配出 Model，
     * 不能再抛「试跑暂不支持协议 ANTHROPIC」。
     */
    @Test
    @DisplayName("ANTHROPIC 协议能装配出可用 Model，不再抛不支持")
    void anthropicProtocolResolvesToModel() {
        givenInstance("claude-3-5-sonnet-latest", "ANTHROPIC", "https://api.anthropic.com");

        Model model = factory.open(INSTANCE_ID);

        assertNotNull(model, "ANTHROPIC 应能装配出引擎模型");
        assertEquals("claude-3-5-sonnet-latest", model.getModelName());
    }

    /**
     * 与 OpenAI 兼容路径对齐：同一份装配入口、同样成功。
     */
    @Test
    @DisplayName("OPENAI_COMPAT 仍能装配，与 ANTHROPIC 走同一闸门")
    void openAiCompatibleStillResolves() {
        givenInstance("deepseek-chat", "OPENAI_COMPAT", "https://api.deepseek.com/v1");

        Model model = factory.open(INSTANCE_ID);

        assertNotNull(model);
        assertEquals("deepseek-chat", model.getModelName());
    }

    /**
     * 灵积兼容模式（baseUrl 含 compatible-mode）走 OpenAI 兼容族，同样能装配。
     */
    @Test
    @DisplayName("DASHSCOPE 的 compatible-mode 能装配")
    void dashScopeCompatibleModeResolves() {
        givenInstance("qwen-plus", "DASHSCOPE", "https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertNotNull(factory.open(INSTANCE_ID));
    }

    /**
     * 灵积原生模式继续走 AgentScope 自带实现，不因本次改动被换掉。
     */
    @Test
    @DisplayName("DASHSCOPE 原生模式仍走 AgentScope 自带实现")
    void dashScopeNativeStillResolves() {
        givenInstance("qwen-max", "DASHSCOPE", "https://dashscope.aliyuncs.com");

        assertNotNull(factory.open(INSTANCE_ID));
    }

    /**
     * CUSTOM 仍必须被拒：本期没有对应 Adapter，放行只会在运行期炸得更晚更难查。
     */
    @Test
    @DisplayName("CUSTOM 仍明确拒绝，且文案列出真实可用协议")
    void customProtocolStillRejected() {
        givenInstance("whatever", "CUSTOM", "https://example.com");

        TrialFailure failure = assertThrows(TrialFailure.class, () -> factory.open(INSTANCE_ID));

        String message = failure.getMessage();
        assertTrue(message.contains("CUSTOM"), "文案应指明是哪个协议不支持：" + message);
        // 关键回归点：可用清单来自工厂注册表，必须包含 ANTHROPIC
        assertTrue(message.contains("ANTHROPIC"), "可用协议清单必须包含 ANTHROPIC：" + message);
        assertTrue(message.contains("OPENAI_COMPAT"), "可用协议清单必须包含 OPENAI_COMPAT：" + message);
        assertTrue(message.contains("DASHSCOPE"), "可用协议清单必须包含 DASHSCOPE：" + message);
        // 旧文案把 Anthropic 排除在外，这次不能再出现
        assertFalse(message.contains("请改用 OPENAI_COMPAT 或 DASHSCOPE"),
                "不得再使用写死两项的旧文案：" + message);
        assertFalse(message.contains("CUSTOM /") || message.contains("/ CUSTOM"),
                "CUSTOM 不能被列为可用协议：" + message);
    }

    /**
     * 供应商 baseUrl 缺失时给出明确文案，而不是让 WebClient 在运行期拼出非法地址。
     */
    @Test
    @DisplayName("ANTHROPIC 缺 baseUrl 时给出明确提示")
    void anthropicMissingBaseUrlFailsClearly() {
        givenInstance("claude-3-5-sonnet-latest", "ANTHROPIC", "  ");

        TrialFailure failure = assertThrows(TrialFailure.class, () -> factory.open(INSTANCE_ID));
        assertTrue(failure.getMessage().contains("baseUrl"), "应提示缺少 baseUrl：" + failure.getMessage());
    }

    /**
     * 凭证解密失败时不得把密文或异常原文透出。
     */
    @Test
    @DisplayName("凭证解密失败时提示不可解密且不含密文")
    void decryptFailureDoesNotLeakCipher() {
        givenInstance("claude-3-5-sonnet-latest", "ANTHROPIC", "https://api.anthropic.com");
        when(secretStore.decrypt(any(), any())).thenThrow(new IllegalArgumentException("bad cipher v2:leak:xx"));

        TrialFailure failure = assertThrows(TrialFailure.class, () -> factory.open(INSTANCE_ID));

        assertFalse(failure.getMessage().contains("leak"), "文案不得包含密文：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("无法解密"), "应给出可读提示：" + failure.getMessage());
    }

    /**
     * 解密时必须带与写入一致的用途绑定串，否则线上会解不开。
     */
    @Test
    @DisplayName("解密使用绑定到供应商的用途串")
    void decryptUsesProviderBoundContext() {
        givenInstance("claude-3-5-sonnet-latest", "ANTHROPIC", "https://api.anthropic.com");

        factory.open(INSTANCE_ID);

        org.mockito.Mockito.verify(secretStore).decrypt("cipher-text",
                SecretContexts.modelCredential(PROVIDER_ID));
    }

    // ---------- 辅助 ----------

    /**
     * 备一份「供应商 + 实例 + 凭证」的可用组合。
     *
     * @param modelId  实例的 modelId
     * @param protocol 供应商协议入库值
     * @param baseUrl  供应商地址
     */
    private void givenInstance(String modelId, String protocol, String baseUrl) {
        ModelInstance instance = new ModelInstance();
        instance.setId(INSTANCE_ID);
        instance.setProviderId(PROVIDER_ID);
        instance.setCredentialId(CREDENTIAL_ID);
        instance.setModelId(modelId);
        instance.setStatus(1);
        when(instances.findById(INSTANCE_ID)).thenReturn(Optional.of(instance));

        ModelProvider provider = new ModelProvider();
        provider.setId(PROVIDER_ID);
        provider.setName("test-provider");
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        provider.setStatus(1);
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));

        ModelCredential credential = new ModelCredential();
        credential.setId(CREDENTIAL_ID);
        credential.setProviderId(PROVIDER_ID);
        credential.setApiKeyCipher("cipher-text");
        credential.setStatus(1);
        when(credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(credential));
    }
}
