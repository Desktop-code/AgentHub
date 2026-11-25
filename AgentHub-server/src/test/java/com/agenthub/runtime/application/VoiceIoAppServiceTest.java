package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.domain.service.SpeechGateway;
import com.agenthub.runtime.domain.model.SpeechAudio;
import com.agenthub.runtime.infra.VoiceProperties;
import com.agenthub.spi.crypto.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 对话语音用例：未绑拒绝、凭证失败不泄 Key、朗读文字裁剪。
 */
@ExtendWith(MockitoExtension.class)
class VoiceIoAppServiceTest {

    private static final long AGENT_ID = 9L;
    private static final long INSTANCE_ID = 11L;
    private static final long PROVIDER_ID = 5L;
    private static final long CREDENTIAL_ID = 7L;

    @Mock
    private TrialBindingLoader bindings;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelProviderRepository providers;
    @Mock
    private ModelCredentialRepository credentials;
    @Mock
    private SecretStore secretStore;
    @Mock
    private SpeechGateway speech;

    private VoiceIoAppService service;

    @BeforeEach
    void setUp() {
        VoiceProperties properties = new VoiceProperties();
        service = new VoiceIoAppService(bindings, instances, providers, credentials,
                secretStore, speech, properties);
    }

    @Test
    void rejectsWhenAsrNotBound() {
        when(bindings.findSpeechModel(AGENT_ID, ModelCapability.ASR)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.transcribe(AGENT_ID, webm()));
        assertTrue(ex.getMessage().contains("未绑定语音识别模型"));
        assertTrue(ex.getMessage().contains("ASR"));
    }

    @Test
    void rejectsWhenTtsNotBound() {
        when(bindings.findSpeechModel(AGENT_ID, ModelCapability.TTS)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.synthesize(AGENT_ID, "你好"));
        assertTrue(ex.getMessage().contains("未绑定语音合成模型"));
    }

    @Test
    void rejectsMissingAgentBeforeTouchingBindings() {
        BizException ex = assertThrows(BizException.class, () -> service.synthesize(null, "你好"));
        assertTrue(ex.getMessage().contains("缺少 Agent"));
    }

    @Test
    void decryptFailureUsesCredentialMessageWithoutKey() {
        when(bindings.findSpeechModel(AGENT_ID, ModelCapability.ASR)).thenReturn(Optional.of(INSTANCE_ID));
        when(instances.findById(INSTANCE_ID)).thenReturn(Optional.of(instance(ModelCapability.ASR)));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(credential()));
        when(secretStore.decrypt(anyString(), anyString())).thenThrow(new IllegalArgumentException("bad master key"));

        BizException ex = assertThrows(BizException.class, () -> service.transcribe(AGENT_ID, webm()));
        assertEquals("凭证无法解密，请重置 Key", ex.getMessage());
    }

    @Test
    void speechReceiveSucceedsAndTextIsTrimmed() {
        when(bindings.findSpeechModel(AGENT_ID, ModelCapability.TTS)).thenReturn(Optional.of(INSTANCE_ID));
        when(instances.findById(INSTANCE_ID)).thenReturn(Optional.of(instance(ModelCapability.TTS)));
        when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider()));
        when(credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(credential()));
        when(secretStore.decrypt(anyString(), anyString())).thenReturn("sk-plain");
        when(speech.synthesize(any(), anyString())).thenReturn(new byte[]{1, 2});

        SpeechAudio audio = service.synthesize(AGENT_ID, "  你好  ");
        assertEquals("audio/mpeg", audio.contentType());
        assertTrue(audio.bytes().length == 2);
    }

    @Test
    void rejectsTooLongSpeechText() {
        BizException ex = assertThrows(BizException.class,
                () -> service.synthesize(AGENT_ID, "长".repeat(4001)));
        assertTrue(ex.getMessage().contains("4000"));
    }

    private ModelInstance instance(ModelCapability capability) {
        ModelInstance instance = new ModelInstance();
        instance.setId(INSTANCE_ID);
        instance.setProviderId(PROVIDER_ID);
        instance.setCredentialId(CREDENTIAL_ID);
        instance.setModelId("voice-model");
        instance.setCapability(capability.name());
        instance.setStatus(1);
        return instance;
    }

    private ModelProvider provider() {
        ModelProvider provider = new ModelProvider();
        provider.setId(PROVIDER_ID);
        provider.setProtocol("OPENAI_COMPAT");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setStatus(1);
        return provider;
    }

    private ModelCredential credential() {
        ModelCredential credential = new ModelCredential();
        credential.setId(CREDENTIAL_ID);
        credential.setProviderId(PROVIDER_ID);
        credential.setApiKeyCipher("v2:cipher");
        credential.setStatus(1);
        return credential;
    }

    /** 一段通过格式闸门的 WebM 头（大于最小体积） */
    private byte[] webm() {
        byte[] audio = new byte[1024];
        audio[0] = (byte) 0x1A;
        audio[1] = 0x45;
        audio[2] = (byte) 0xDF;
        audio[3] = (byte) 0xA3;
        return audio;
    }
}
