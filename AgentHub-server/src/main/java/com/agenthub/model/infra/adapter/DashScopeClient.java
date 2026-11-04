package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.PurposeIoKind;
import com.agenthub.model.domain.service.PurposeRoute;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 灵积探活。所属域：model。
 * 经 {@link ModelPurposeMatrix} 分流：Chat / Embedding / 视觉按 baseUrl 选兼容或原生；
 * RERANK <b>一律</b>打原生 text-rerank（与 compatible-mode 无关），禁止走 Chat。
 */
@Component
public class DashScopeClient implements ModelClient {

    private final ModelHttpSupport http;

    /**
     * @param http 共用发送与脱敏
     */
    public DashScopeClient(ModelHttpSupport http) {
        this.http = http;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProbeResult probe(ProbeCommand command) {
        PurposeRoute route = ModelPurposeMatrix.resolve(ModelProtocol.DASHSCOPE, command.capability());
        if (!route.adapted()) {
            return ProbeResult.unsupported(route.message());
        }
        boolean compatible = command.baseUrl() != null && command.baseUrl().contains("compatible-mode");
        return switch (route.kind()) {
            case CHAT -> compatible ? compatibleChat(command) : nativeChat(command);
            case EMBEDDING -> compatible ? compatibleEmbed(command) : nativeEmbed(command);
            case RERANK -> nativeRerank(command);
            case VISION -> nativeVision(command, compatible);
            default -> ProbeResult.unsupported(route.message());
        };
    }

    private ProbeResult compatibleChat(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 1));
        return http.postJson(http.join(command.baseUrl(), "/chat/completions"), command.apiKey(), body, "ok");
    }

    private ProbeResult compatibleEmbed(ProbeCommand command) {
        String body = http.json(Map.of("model", command.modelId(), "input", "ping"));
        return http.postJson(http.join(command.baseUrl(), "/embeddings"), command.apiKey(), body, "ok");
    }

    private ProbeResult nativeChat(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("messages", List.of(Map.of("role", "user", "content", "ping"))),
                "parameters", Map.of("max_tokens", 1, "result_format", "message")));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.TEXT_GENERATION);
        return http.postJson(url, command.apiKey(), body, "ok");
    }

    private ProbeResult nativeEmbed(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("texts", List.of("ping"))));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.TEXT_EMBEDDING);
        return http.postJson(url, command.apiKey(), body, "ok");
    }

    /**
     * 文本排序连通测：即使供应商填了 compatible-mode，也打原生 text-rerank。
     * body 与 RAG 出站一致：model + input.query + input.documents。
     */
    private ProbeResult nativeRerank(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("query", "ping", "documents", List.of("ping")),
                "parameters", Map.of("top_n", 1)));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.TEXT_RERANK);
        return http.postJson(url, command.apiKey(), body, "ok");
    }

    /**
     * 视觉连通测：只有 compatible-mode 基址才有 OpenAI 多模态 chat/completions。
     * <p>
     * 原生 generation 端点的多模态报文（input.messages + image 列表）各版本差异大且未在本仓验证过，
     * 因此基址不是 compatible-mode 时明确「用途未适配」而不是拼一个可能 400 的请求——
     * 这类失败会被误读成「模型坏了」。不发 HTTP 也能避免用户白等一次超时。
     *
     * @param command    出站参数
     * @param compatible 基址是否为兼容模式
     * @return 探活结果
     */
    private ProbeResult nativeVision(ProbeCommand command, boolean compatible) {
        if (!compatible) {
            return ProbeResult.unsupported("用途未适配：DASHSCOPE×VISION，视觉探活仅支持 compatible-mode 基址"
                    + "（如 https://dashscope.aliyuncs.com/compatible-mode/v1）");
        }
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", VisionProbePayload.openAiContent());
        String body = http.json(Map.of(
                "model", command.modelId(),
                "messages", List.of(userMessage),
                "max_tokens", 16));
        return http.postJson(http.join(command.baseUrl(), "/chat/completions"), command.apiKey(), body, "视觉连通");
    }
}
