package com.agenthub.runtime.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.model.SpeechRequest;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.domain.service.ModelCredentialResolver;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.PurposeRoute;
import com.agenthub.model.domain.service.SpeechGateway;
import com.agenthub.model.domain.service.SpeechOptions;
import com.agenthub.runtime.domain.model.SpeechAudio;
import com.agenthub.runtime.domain.service.VoiceAudioPolicy;
import com.agenthub.runtime.domain.service.VoiceTextPolicy;
import com.agenthub.runtime.infra.VoiceProperties;
import com.agenthub.spi.crypto.SecretStore;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * 对话页语音用例。所属域：runtime。
 *
 * <p>职责边界：本类只做「取 Agent 绑定的语音模型 → 校验启用/凭证 → 调语音端口」，
 * 不自建 Key 存储、不新开配置中心、不改对话链路（会话 / SSE / 续跑语义都不经过这里）。
 *
 * <p>读取优先级（硬性）：<b>只认 Agent 自己的 ASR / TTS 槽</b>，不做平台默认或环境变量兜底。
 * 这样「能不能用、用的是哪个模型」在 Agent 的模型配置页一眼可见；
 * 没绑就是没绑——听写按钮置灰、朗读走浏览器，而不是偷偷借用别的模型冒充。
 *
 * <p>安全边界：音频与文字都只在本进程内存里流转，不落业务表、不进对象存储；
 * 凭证解密失败按现网话术提示重置 Key，不回退 env、日志不打印 Key。
 *
 * <p>线程：出站是阻塞调用，由 MVC 请求线程直接执行（语音请求本身已是长请求），
 * 不做异步化，避免线程池与超时语义再叠一层。
 */
@Service
public class VoiceIoAppService {

    private final TrialBindingLoader bindings;
    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final SecretStore secretStore;
    private final SpeechGateway speech;
    private final VoiceProperties properties;

    /**
     * @param bindings    按槽解析 Agent 绑定的模型（含启用与凭证齐全校验）
     * @param instances   实例
     * @param providers   供应商（协议与 baseUrl）
     * @param credentials 密文凭证
     * @param secretStore 现有 AES-GCM，主密钥来自 AGENTHUB_SECRET_KEY
     * @param speech      语音出站端口
     * @param properties  录音体积与朗读字数上限
     */
    public VoiceIoAppService(TrialBindingLoader bindings, ModelInstanceRepository instances,
                             ModelProviderRepository providers, ModelCredentialRepository credentials,
                             SecretStore secretStore, SpeechGateway speech, VoiceProperties properties) {
        this.bindings = bindings;
        this.instances = instances;
        this.providers = providers;
        this.credentials = credentials;
        this.secretStore = secretStore;
        this.speech = speech;
        this.properties = properties;
    }

    /**
     * 整段音频 → 文字。
     *
     * @param agentId Agent；语音模型从该 Agent 的 ASR 槽取
     * @param audio   录音字节（内存）
     * @return 识别文字
     * @throws BizException 未绑定识别模型、录音不合格、凭证不可解密或上游失败
     */
    public String transcribe(Long agentId, byte[] audio) {
        VoiceAudioPolicy.Format format = VoiceAudioPolicy.require(audio, properties.getMaxAudioBytes());
        SpeechRequest request = requireSpeech(agentId, ModelCapability.ASR);
        // fileName 的扩展名决定上游按什么容器解码，必须与文件头识别结果一致
        return speech.transcribe(request, audio, "voice." + format.extension(), format.mimeType());
    }

    /**
     * 整段文字 → 音频。
     *
     * @param agentId Agent；语音模型从该 Agent 的 TTS 槽取
     * @param text    待朗读文字（前端已转纯文本）
     * @return 音频字节与 MIME
     * @throws BizException 未绑定合成模型、文字超限、凭证不可解密或上游失败
     */
    public SpeechAudio synthesize(Long agentId, String text) {
        String ttsText = VoiceTextPolicy.requireSpeakable(text, properties.getMaxSpeechChars());
        SpeechRequest request = requireSpeech(agentId, ModelCapability.TTS);
        byte[] audio = speech.synthesize(request, ttsText);
        return new SpeechAudio(audio, contentTypeOf(request.format()));
    }

    /**
     * 解析并校验语音槽 → 出站参数。
     *
     * @param agentId Agent
     * @param purpose ASR 或 TTS
     * @return 已解密 Key 的出站参数
     * @throws BizException 未绑定、实例/供应商/凭证不可用、协议不支持或解密失败
     */
    private SpeechRequest requireSpeech(Long agentId, ModelCapability purpose) {
        if (agentId == null || agentId <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 Agent，无法使用语音功能");
        }
        Optional<Long> instanceId = bindings.findSpeechModel(agentId, purpose);
        if (instanceId.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, purpose == ModelCapability.ASR
                    ? "当前 Agent 未绑定语音识别模型，请前往「模型配置」Tab 绑定 ASR 模型"
                    : "当前 Agent 未绑定语音合成模型");
        }
        ModelInstance instance = instances.findById(instanceId.get())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "语音模型实例不可用，请检查是否已启用"));
        // 双保险：绑定行用途与实例用途必须一致，避免历史错绑把 CHAT 实例当语音模型用
        ModelCapability actual = ModelCapability.tryParse(instance.getCapability()).orElse(null);
        if (actual != purpose) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "绑定实例的用途是 " + (actual == null ? "未知" : actual.name()) + "，与语音槽 " + purpose.name() + " 不一致");
        }
        // 先拦住空外键：findById(null) 在不同 Mapper 实现下行为不一致，别让脏数据变成 500
        if (instance.getProviderId() == null || instance.getCredentialId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音模型缺少供应商或凭证配置，请重新绑定");
        }
        ModelProvider provider = providers.findById(instance.getProviderId())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "语音模型供应商不可用，请检查是否已启用"));
        ModelCredential credential = credentials.findById(instance.getCredentialId())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "语音模型凭证不可用，请检查是否已启用"));
        ModelProtocol protocol = protocolOf(provider.getProtocol());
        PurposeRoute route = ModelPurposeMatrix.resolve(protocol, purpose);
        if (!route.adapted()) {
            throw new BizException(ErrorCode.BAD_REQUEST, route.message());
        }
        String apiKey = decrypt(credential);
        return new SpeechRequest(protocol, provider.getBaseUrl(), apiKey, instance.getModelId(), purpose,
                SpeechOptions.voiceOf(instance.getExtraJson()), SpeechOptions.formatOf(instance.getExtraJson()));
    }

    /**
     * 库内协议文本 → 枚举。未知文本按 CUSTOM 处理，交给矩阵给出「用途未适配」。
     *
     * @param raw 协议文本
     * @return 协议枚举
     */
    private ModelProtocol protocolOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return ModelProtocol.CUSTOM;
        }
        try {
            return ModelProtocol.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ModelProtocol.CUSTOM;
        }
    }

    /**
     * 解密出站 Key。AAD 绑供应商 id，与写入时一致；失败不打印密文。
     *
     * @param credential 凭证
     * @return 明文 Key
     * @throws BizException 解密失败（文案与模型中心一致）
     */
    private String decrypt(ModelCredential credential) {
        try {
            return ModelCredentialResolver.decryptKey(secretStore, credential.getApiKeyCipher(),
                    credential.getProviderId());
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "凭证无法解密，请重置 Key");
        }
    }

    /**
     * 合成格式 → 响应 Content-Type。前端 <audio> 依赖它选解码器。
     *
     * @param format 规范化格式（mp3 / wav / opus / aac / flac）
     * @return MIME
     */
    private String contentTypeOf(String format) {
        String key = format == null ? "" : format.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "wav" -> "audio/wav";
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            default -> "audio/mpeg";
        };
    }
}
