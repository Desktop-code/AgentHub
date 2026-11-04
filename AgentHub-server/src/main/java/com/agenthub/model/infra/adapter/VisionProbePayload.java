package com.agenthub.model.infra.adapter;

import java.util.List;
import java.util.Map;

/**
 * 视觉连通测的最小报文素材。所属域：model 的 infra。
 * <p>
 * OPENAI_COMPAT 与 DashScope compatible-mode 都是 OpenAI 多模态形态，共用同一份 1x1 图片与提示词；
 * 集中一处避免各客户端各写一份 base64，也保证「探活到底发了什么」只有一个事实来源。
 * 只含常量与不可变 List/Map，可安全并发复用。
 */
final class VisionProbePayload {

    /**
     * 1x1 透明 PNG（70 字节）的 data URL。
     * 用内联 base64 而不是外链，探活不依赖公网可达的图片地址。
     */
    static final String IMAGE_DATA_URL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

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
}
