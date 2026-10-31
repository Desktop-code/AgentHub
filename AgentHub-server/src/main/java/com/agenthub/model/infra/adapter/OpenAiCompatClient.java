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
 * 按 {@link ModelPurposeMatrix} 分流：CHAT→chat/completions，EMBEDDING→embeddings；
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
        return ProbeResult.unsupported(route.message());
    }
}
