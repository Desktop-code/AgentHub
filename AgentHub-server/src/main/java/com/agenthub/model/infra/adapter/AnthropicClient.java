package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.ProbePrompts;
import com.agenthub.model.domain.service.PurposeRoute;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Anthropic 探活。所属域：model。
 * CHAT 族走 messages；VISION 走 messages 的 base64 image source；
 * GUARDRAIL 按官方文档化的 Messages 分类提示探活（Anthropic 无独立审核端点）；
 * Embedding / Rerank / 语音经矩阵返回「该协议不支持此用途」，禁 GET 冒充成功。
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
        if (!route.adapted()) {
            return ProbeResult.unsupported(route.message());
        }
        return switch (route.kind()) {
            case CHAT -> chat(command);
            case VISION -> vision(command);
            case GUARDRAIL -> guardrail(command);
            default -> ProbeResult.unsupported(route.message());
        };
    }

    /**
     * 文本生成族（CHAT / ROUTER / REWRITE / SUMMARY / MEMORY）统一打 messages。
     *
     * @param command 出站参数
     * @return 探活结果
     */
    private ProbeResult chat(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "max_tokens", 1,
                // 提示词与用途码保持原用途，避免 REWRITE / SUMMARY 等被当成 CHAT
                "messages", List.of(Map.of("role", "user", "content",
                        ProbePrompts.chat(command.capability())))));
        return postMessages(command, body, command.capability().name() + " 连通");
    }

    /**
     * 视觉探活：messages 的 content 数组里放 text 块 + image 块。
     * Anthropic 的图片必须是 {@code source.type=base64}，不能直接塞 data URL。
     *
     * @param command 出站参数
     * @return 探活结果
     */
    private ProbeResult vision(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "max_tokens", 16,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", VisionProbePayload.anthropicContent()))));
        return postMessages(command, body, "视觉连通");
    }

    /**
     * 安全护栏探活：Anthropic 没有独立审核端点，按官方文档化的做法
     * （用 Messages API + 分类提示）验证模型与端点可达。
     * <p>
     * 本仓对话链路尚无拦截点，成功文案明确写「仅连通性」，不冒充已上线内容审查。
     *
     * @param command 出站参数
     * @return 探活结果
     */
    private ProbeResult guardrail(ProbeCommand command) {
        String prompt = "你是内容安全分类器。请判断以下内容是否安全，只回答 SAFE：ping";
        String body = http.json(Map.of(
                "model", command.modelId(),
                "max_tokens", 8,
                "messages", List.of(Map.of("role", "user", "content", prompt))));
        return postMessages(command, body, "安全护栏连通（仅连通性，未接入运行时拦截）");
    }

    /**
     * 统一带 Anthropic 鉴权头发送 messages 请求。
     *
     * @param command   出站参数
     * @param body      JSON 正文
     * @param okMessage 2xx 时给用户看的说明
     * @return 探活结果
     */
    private ProbeResult postMessages(ProbeCommand command, String body, String okMessage) {
        Map<String, String> headers = Map.of(
                "x-api-key", command.apiKey(),
                "anthropic-version", "2023-06-01");
        return http.postJson(messagesUrl(command.baseUrl()), headers, command.apiKey(), body, okMessage);
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
