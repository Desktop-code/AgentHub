package com.agenthub.model.infra.adapter;

import java.util.List;
import java.util.Map;

/**
 * 视觉连通测的最小报文素材。所属域：model 的 infra。
 * <p>
 * 开放多模态的三协议共用同一份 1x1 图片与提示词，但报文形态不同：
 * <ul>
 *   <li>OPENAI_COMPAT / DashScope 兼容模式：content 数组里的 {@code image_url}；</li>
 *   <li>DashScope 原生 multimodal-generation：content 数组里的 {@code image}（data URL）；</li>
 *   <li>Anthropic messages：content 数组里的 {@code image.source}（base64）。</li>
 * </ul>
 * 集中一处避免各客户端各写一份 base64，也保证「探活到底发了什么」只有一个事实来源。
 * 只含常量与不可变 List/Map，可安全并发复用。
 */
final class VisionProbePayload {

    /** 探活图片 MIME：1x1 透明 PNG */
    static final String PNG_MEDIA_TYPE = "image/png";

    /** 1x1 透明 PNG 的 base64（不含 data URL 前缀），Anthropic 必须裸 base64 */
    static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    /**
     * 1x1 透明 PNG 的 data URL。
     * 用内联 base64 而不是外链，探活不依赖公网可达的图片地址。
     */
    static final String IMAGE_DATA_URL = "data:" + PNG_MEDIA_TYPE + ";base64," + PNG_BASE64;

    /** 只要求模型「能读图并回一句话」，不索取实质内容，避免多花 token */
    static final String TEXT = "你好";

    private VisionProbePayload() {
    }

    /**
     * OpenAI 形态的多模态 content 数组：文字在前、图片在后。
     * content 必须是数组，纯字符串无法携带 image_url 块。
     *
     * @return 不可变 content 列表，可直接放进 user message
     */
    static List<Map<String, Object>> openAiContent() {
        Map<String, Object> textPart = Map.of("type", "text", "text", TEXT);
        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", IMAGE_DATA_URL));
        return List.of(textPart, imagePart);
    }

    /**
     * DashScope 原生 multimodal-generation 的 content 数组：
     * 图片用 {@code {"image": dataUrl}}，文字用 {@code {"text": ...}}。
     *
     * @return 不可变 content 列表
     */
    static List<Map<String, Object>> dashScopeNativeContent() {
        return List.of(
                Map.of("image", IMAGE_DATA_URL),
                Map.of("text", TEXT));
    }

    /**
     * Anthropic messages 的 content 数组：text 块 + image 块。
     * Anthropic 的 image 必须走 {@code source.type=base64}，不能直接塞 data URL。
     *
     * @return 不可变 content 列表
     */
    static List<Map<String, Object>> anthropicContent() {
        Map<String, Object> imagePart = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", PNG_MEDIA_TYPE,
                        "data", PNG_BASE64));
        return List.of(
                Map.of("type", "text", "text", TEXT),
                imagePart);
    }
}
