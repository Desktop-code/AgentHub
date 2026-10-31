package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.PurposeIoKind;
import com.agenthub.model.domain.service.PurposeRoute;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Anthropic 探活。所属域：model。
 * 仅 CHAT 族走 messages；Embedding / Rerank / 多模态经矩阵返回「用途未适配」，禁 GET 冒充成功。
 */
@Component
public class AnthropicClient implements ModelClient {

    private final ModelHttpSupport http;

    /**
     * @param http 共用发送与脱敏
     */
    public AnthropicClient(ModelHttpSupport http) {
        this.http = http;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProbeResult probe(ProbeCommand command) {
        PurposeRoute route = ModelPurposeMatrix.resolve(ModelProtocol.ANTHROPIC, command.capability());
        if (!route.adapted() || route.kind() != PurposeIoKind.CHAT) {
            return ProbeResult.unsupported(route.message());
        }
        Map<String, String> headers = Map.of(
                "x-api-key", command.apiKey(),
                "anthropic-version", "2023-06-01");
        String body = http.json(Map.of(
                "model", command.modelId(),
                "max_tokens", 1,
                "messages", List.of(Map.of("role", "user", "content", "ping"))));
        return http.postJson(messagesUrl(command.baseUrl()), headers, command.apiKey(), body, "ok");
    }

    /**
     * baseUrl 已经带 /v1 时不再重复拼接。
     */
    private String messagesUrl(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (base.endsWith("/v1")) {
            return base + "/messages";
        }
        return base + "/v1/messages";
    }
}
