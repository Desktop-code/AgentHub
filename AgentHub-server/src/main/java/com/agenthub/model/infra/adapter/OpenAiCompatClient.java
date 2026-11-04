package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.PurposeIoKind;
import com.agenthub.model.domain.service.PurposeRoute;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容探活。所属域：model。
 * 按 {@link ModelPurposeMatrix} 分流：CHAT→chat/completions，EMBEDDING→embeddings，
 * VISION→chat/completions（图文混排）；
 * 其余用途明确「用途未适配」，禁止降级 HTTP 可达冒充成功。
 */
@Component
public class OpenAiCompatClient implements ModelClient {

    private final ModelHttpSupport http;

    /**
     * @param http 共用发送与脱敏
     */
    public OpenAiCompatClient(ModelHttpSupport http) {
        this.http = http;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProbeResult probe(ProbeCommand command) {
        PurposeRoute route = ModelPurposeMatrix.resolve(ModelProtocol.OPENAI_COMPAT, command.capability());
        if (!route.adapted()) {
            return ProbeResult.unsupported(route.message());
        }
        if (route.kind() == PurposeIoKind.CHAT) {
            String body = http.json(Map.of(
                    "model", command.modelId(),
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1));
            return http.postJson(http.join(command.baseUrl(), "/chat/completions"), command.apiKey(), body, "ok");
        }
        if (route.kind() == PurposeIoKind.EMBEDDING) {
            String body = http.json(Map.of("model", command.modelId(), "input", "ping"));
            return http.postJson(http.join(command.baseUrl(), "/embeddings"), command.apiKey(), body, "ok");
        }
        if (route.kind() == PurposeIoKind.VISION) {
            return probeVision(command);
        }
        return ProbeResult.unsupported(route.message());
    }

    /**
     * 视觉探活：真打一次多模态 chat/completions。
     * <p>
     * 报文刻意用最小体积（一个「你好」+ 1x1 PNG）：多模态模型对空图片会直接 400，
     * 但也不需要真实图片内容才能证明「鉴权 + 模型 + 读图链路」都通。
     * 成功判定沿用 {@link ModelHttpSupport#postJson}：2xx 即通，401/403/404 与其它非 2xx 分别给出脱敏原因。
     *
     * @param command 已解密的出站参数
     * @return 探活结果；成功时 message 标注为视觉连通
     */
    private ProbeResult probeVision(ProbeCommand command) {
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", VisionProbePayload.openAiContent());
        String body = http.json(Map.of(
                "model", command.modelId(),
                "messages", List.of(userMessage),
                // 个别厂商要求图片探活带 max_tokens，否则按默认长输出计费
                "max_tokens", 16));
        return http.postJson(http.join(command.baseUrl(), "/chat/completions"), command.apiKey(), body, "视觉连通");
    }
}
