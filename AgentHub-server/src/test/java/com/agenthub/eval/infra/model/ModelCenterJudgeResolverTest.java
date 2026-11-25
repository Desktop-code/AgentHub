package com.agenthub.eval.infra.model;

import com.agenthub.eval.domain.model.OpenJudgeJudge;
import com.agenthub.eval.domain.service.JudgeModelUnavailableException;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.infra.crypto.AesGcmSecretStore;
import com.agenthub.spi.crypto.SecretContexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 打分模型凭据解析单测。所属域：eval 的 infra。
 * <p>用真实 AES-GCM（固定 32 字节测试主密钥）+ mock 仓储，验证：
 * 密文能解出、AAD 绑定供应商、基址归一到 OpenAI 兼容、失败抛「不可用」而非回落全局 Key。
 */
class ModelCenterJudgeResolverTest {

    /** 测试主密钥（64 hex = 32 字节），仅测试用 */
    private static final String TEST_MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PLAIN_KEY = "sk-unit-test-judge-key-9f9f";
    private static final Long INSTANCE_ID = 501L;
    private static final Long PROVIDER_ID = 9L;
    private static final Long CREDENTIAL_ID = 3L;

    private final ModelInstanceRepository instances = mock(ModelInstanceRepository.class);
    private final ModelProviderRepository providers = mock(ModelProviderRepository.class);
    private final ModelCredentialRepository credentials = mock(ModelCredentialRepository.class);
    private final AesGcmSecretStore secretStore = new AesGcmSecretStore(TEST_MASTER_KEY);

    private ModelCenterJudgeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ModelCenterJudgeResolver(instances, providers, credentials, secretStore);
    }

    /** DashScope 原生基址 → 同主机 compatible-mode/v1；Key 解出且 toString 脱敏。 */
    @Test
    void resolvesDashScopeNativeIntoCompatibleBaseUrl() {
        stubInstance("qwen-plus");
        stubProvider("DASHSCOPE", "https://dashscope.aliyuncs.com/api/v1");
        stubCredential(secretStore.encrypt(PLAIN_KEY, SecretContexts.modelCredential(PROVIDER_ID)));

        OpenJudgeJudge judge = resolver.resolve(INSTANCE_ID);

        assertEquals("qwen-plus", judge.getModel());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", judge.getBaseUrl());
        assertEquals(PLAIN_KEY, judge.getApiKey());
        assertFalse(judge.toString().contains(PLAIN_KEY), "toString 必须脱敏");
        assertTrue(judge.toString().contains("***"));
    }

    /** 已经是兼容基址：原样保留，不再拼前缀。 */
    @Test
    void keepsExistingCompatibleBaseUrl() {
        stubInstance("qwen-plus");
        stubProvider("DASHSCOPE", "https://dashscope.aliyuncs.com/compatible-mode/v1/");
        stubCredential(secretStore.encrypt(PLAIN_KEY, SecretContexts.modelCredential(PROVIDER_ID)));

        OpenJudgeJudge judge = resolver.resolve(INSTANCE_ID);

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", judge.getBaseUrl());
    }

    /** 供应商没填基址：返回 null，交给 sidecar 环境变量。 */
    @Test
    void blankBaseUrlFallsBackToNull() {
        stubInstance("gpt-4o-mini");
        stubProvider("OPENAI_COMPAT", "  ");
        stubCredential(secretStore.encrypt(PLAIN_KEY, SecretContexts.modelCredential(PROVIDER_ID)));

        assertNull(resolver.resolve(INSTANCE_ID).getBaseUrl());
    }

    /** 实例不存在：直接判不可用，不回落任何全局 Key。 */
    @Test
    void missingInstanceThrowsUnavailable() {
        when(instances.findById(INSTANCE_ID)).thenReturn(Optional.empty());

        JudgeModelUnavailableException ex = assertThrows(JudgeModelUnavailableException.class,
                () -> resolver.resolve(INSTANCE_ID));
        assertTrue(ex.getMessage().contains(String.valueOf(INSTANCE_ID)));
    }

    /** 实例停用：不可用。 */
    @Test
    void disabledInstanceThrowsUnavailable() {
        ModelInstance instance = stubInstance("qwen-plus");
        instance.setStatus(0);

        assertThrows(JudgeModelUnavailableException.class, () -> resolver.resolve(INSTANCE_ID));
    }

    /** 解密失败（密文被换供应商 / 损坏）：抛不可用，绝不改用 env Key。 */
    @Test
    void decryptFailureThrowsUnavailableWithoutLeakingCipher() {
        stubInstance("qwen-plus");
        stubProvider("OPENAI_COMPAT", "https://api.example.com/v1");
        // 用另一个供应商的 AAD 加密，模拟密文被搬走
        stubCredential(secretStore.encrypt(PLAIN_KEY, SecretContexts.modelCredential(999L)));

        JudgeModelUnavailableException ex = assertThrows(JudgeModelUnavailableException.class,
                () -> resolver.resolve(INSTANCE_ID));
        assertTrue(ex.getMessage().contains("解密失败"));
        assertFalse(ex.getMessage().contains(PLAIN_KEY), "异常消息不得含 Key");
    }

    /** 凭证没配 Key：不可用。 */
    @Test
    void blankCipherThrowsUnavailable() {
        stubInstance("qwen-plus");
        stubProvider("OPENAI_COMPAT", "https://api.example.com/v1");
        ModelCredential credential = new ModelCredential();
        credential.setId(CREDENTIAL_ID);
        credential.setProviderId(PROVIDER_ID);
        credential.setStatus(1);
        credential.setApiKeyCipher(" ");
        when(credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(credential));

        assertThrows(JudgeModelUnavailableException.class, () -> resolver.resolve(INSTANCE_ID));
    }

    /** Anthropic 非 OpenAI 兼容：明确拒绝，避免 sidecar 静默走错协议。 */
    @Test
    void anthropicProviderIsRejected() {
        stubInstance("claude-3-5-sonnet");
        stubProvider("ANTHROPIC", "https://api.anthropic.com");
        stubCredential(secretStore.encrypt(PLAIN_KEY, SecretContexts.modelCredential(PROVIDER_ID)));

        JudgeModelUnavailableException ex = assertThrows(JudgeModelUnavailableException.class,
                () -> resolver.resolve(INSTANCE_ID));
        assertTrue(ex.getMessage().contains("Anthropic"));
    }

    private ModelInstance stubInstance(String modelId) {
        ModelInstance instance = new ModelInstance();
        instance.setId(INSTANCE_ID);
        instance.setProviderId(PROVIDER_ID);
        instance.setCredentialId(CREDENTIAL_ID);
        instance.setModelId(modelId);
        instance.setStatus(1);
        instance.setCapability("CHAT");
        when(instances.findById(INSTANCE_ID)).thenReturn(Optional.of(instance));
        return instance;
    }

    private void stubProvider(String protocol, String baseUrl) {
        ModelProvider provider = new ModelProvider();
        provider.setId(PROVIDER_ID);
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        provider.setStatus(1);
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    }

    private void stubCredential(String cipher) {
        ModelCredential credential = new ModelCredential();
        credential.setId(CREDENTIAL_ID);
        credential.setProviderId(PROVIDER_ID);
        credential.setStatus(1);
        credential.setApiKeyCipher(cipher);
        when(credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(credential));
    }
}
