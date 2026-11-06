package com.agenthub.model.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次语音出站参数。所属域：model。
 *
 * <p>安全边界：{@code apiKey} 是从 {@code model_credential} 解出的明文，只在本次出站栈上使用：
 * 禁止落库、禁止进日志、禁止进 oper_log。因此 {@link #toString()} 显式把 Key 抹掉，
 * 避免有人顺手 {@code log.info("{}", request)} 就把 Key 写进日志。
 *
 * @param protocol    供应商协议（决定路径与二次基址校验）
 * @param baseUrl     供应商地址，已含版本前缀（如 https://api.openai.com/v1）
 * @param apiKey      已解密的厂商 Key，仅当次使用
 * @param modelId     厂商模型 ID（如 whisper-1 / tts-1）
 * @param capability  用途：ASR 或 TTS
 * @param voice       合成音色，可空；空则用 {@code SpeechOptions.DEFAULT_VOICE}
 * @param format      合成音频格式，可空；空则用 {@code SpeechOptions.DEFAULT_FORMAT}
 * @param extraParams 实例「附加参数」中的可选键（按厂家文档填写的原样键名），
 *                    出站时浅合并进该协议 TTS 报文的可选参数位置；仅 TTS 生效，可为空
 */
public record SpeechRequest(
        ModelProtocol protocol,
        String baseUrl,
        String apiKey,
        String modelId,
        ModelCapability capability,
        String voice,
        String format,
        Map<String, Object> extraParams) {

    /**
     * 规范化附加参数：null 统一成空 Map，避免下游每处都判空。
     * 用不可变 LinkedHashMap 保序（报文字段顺序稳定），且允许值为 null（用户 JSON 里可能显式写 null）。
     */
    public SpeechRequest {
        extraParams = extraParams == null || extraParams.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extraParams));
    }

    /**
     * 日志友好输出：Key 一律显示为 ***；附加参数只出键名，不出值（instruction 等可能含业务文本）。
     *
     * @return 不含明文的描述
     */
    @Override
    public String toString() {
        return "SpeechRequest[protocol=" + protocol
                + ", baseUrl=" + baseUrl
                + ", modelId=" + modelId
                + ", capability=" + capability
                + ", voice=" + voice
                + ", format=" + format
                + ", extraParamKeys=" + extraParams.keySet()
                + ", apiKey=***]";
    }
}
