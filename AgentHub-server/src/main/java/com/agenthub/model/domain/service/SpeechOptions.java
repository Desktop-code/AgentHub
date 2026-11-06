package com.agenthub.model.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/**
 * 语音实例的扩展配置。所属域：model。
 *
 * <p>音色 / 输出格式跟模型实例走（{@code model_instance.extra_json}），
 * 与温度、嵌入维度同一套「实例级扩展」惯例，不新开配置表、不新造 Key 存储。
 * 键名固定为 {@code voice} 与 {@code format}；未配置时用下面的默认值，
 * 保证「绑了就能用」，不必先让用户去填音色。
 *
 * <p>线程安全：无状态，静态纯函数。
 */
public final class SpeechOptions {

    /** 默认音色：OpenAI 兼容语音接口的通用取值，多数中转都认 */
    public static final String DEFAULT_VOICE = "alloy";

    /** 默认输出格式：mp3 体积小、浏览器 <audio> 直接可播 */
    public static final String DEFAULT_FORMAT = "mp3";

    /** 允许的输出格式白名单；白名单之外一律回落到默认，避免把乱填的值发给上游 */
    private static final Set<String> ALLOWED_FORMATS = Set.of("mp3", "wav", "opus", "aac", "flac");

    private static final ObjectMapper JSON = new ObjectMapper();

    private SpeechOptions() {
    }

    /**
     * 读取合成音色（通用默认值）。
     *
     * <p>语义上等价于「OpenAI 兼容默认」；DashScope 等有专属默认音色的协议
     * 应改用 {@link #rawVoiceOf(String)} 判空后自行兜底，避免把 alloy 发给不认它的上游。
     *
     * @param extraJson 实例 extra_json，可空或非法
     * @return 音色；未配置返回 {@link #DEFAULT_VOICE}
     */
    public static String voiceOf(String extraJson) {
        String value = rawVoiceOf(extraJson);
        return value.isBlank() ? DEFAULT_VOICE : value;
    }

    /**
     * 读取用户显式配置的音色原文，不套协议默认值。
     * <p>
     * DashScope 原生语音合成的音色是模型相关值（如 longanhuan_v3.1 / longxiaochun），
     * OpenAI 兼容的 {@link #DEFAULT_VOICE}（alloy）在原生口不一定存在；
     * 原生适配器需要先判断「用户到底配没配」，再决定用哪个协议默认值。
     *
     * @param extraJson 实例 extra_json，可空或非法
     * @return 去空白后的音色；未配置或取不到为空串
     */
    public static String rawVoiceOf(String extraJson) {
        return text(extraJson, "voice");
    }

    /**
     * 读取合成输出格式。只接受白名单内的值（大小写不敏感）。
     *
     * @param extraJson 实例 extra_json，可空或非法
     * @return 规范小写格式；未配置或不在白名单返回 {@link #DEFAULT_FORMAT}
     */
    public static String formatOf(String extraJson) {
        String value = text(extraJson, "format").toLowerCase(Locale.ROOT);
        return ALLOWED_FORMATS.contains(value) ? value : DEFAULT_FORMAT;
    }

    /**
     * 取指定键的文本值。解析失败、不是 JSON 对象或值不是文本时返回空串。
     *
     * @param extraJson 扩展 JSON
     * @param key       键名
     * @return 去空白后的值；取不到为空串
     */
    private static String text(String extraJson, String key) {
        if (extraJson == null || extraJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(extraJson).get(key);
            if (node != null && node.isTextual()) {
                return node.asText().trim();
            }
        } catch (Exception ignored) {
            // 扩展 JSON 坏值按「未配置」处理：语音配置不该拖垮探活或对话
        }
        return "";
    }
}
