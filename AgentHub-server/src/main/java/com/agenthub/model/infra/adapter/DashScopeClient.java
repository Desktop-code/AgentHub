package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.ProbePrompts;
import com.agenthub.model.domain.service.PurposeIoKind;
import com.agenthub.model.domain.service.PurposeRoute;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 灵积探活。所属域：model。
 * 经 {@link ModelPurposeMatrix} 分流：
 * <ul>
 *   <li>Chat / Embedding / 视觉 / 护栏：兼容模式走 OpenAI 形态，原生基址走 /api/v1 原生端点；</li>
 *   <li>RERANK <b>一律</b>打原生 text-rerank（与 compatible-mode 无关），禁止走 Chat；</li>
 *   <li>语音识别 / 合成在 {@link SpeechClient}，本类不处理音频。</li>
 * </ul>
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
            case VISION -> vision(command, compatible);
            case GUARDRAIL -> guardrail(command, compatible);
            default -> ProbeResult.unsupported(route.message());
        };
    }

    private ProbeResult compatibleChat(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                // 用途码与提示词都保持原用途，不因共用 chat 端点就改成 CHAT
                "messages", List.of(Map.of("role", "user", "content",
                        ProbePrompts.chat(command.capability()))),
                "max_tokens", 1));
        return http.postJson(http.join(command.baseUrl(), "/chat/completions"), command.apiKey(), body,
                command.capability().name() + " 连通");
    }

    private ProbeResult compatibleEmbed(ProbeCommand command) {
        String body = http.json(Map.of("model", command.modelId(), "input", "ping"));
        return http.postEmbedding(http.join(command.baseUrl(), "/embeddings"), command.apiKey(), body, "ok");
    }

    private ProbeResult nativeChat(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("messages", List.of(Map.of("role", "user", "content",
                        ProbePrompts.chat(command.capability())))),
                "parameters", Map.of("max_tokens", 1, "result_format", "message")));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.TEXT_GENERATION);
        return http.postJson(url, command.apiKey(), body, command.capability().name() + " 连通");
    }

    private ProbeResult nativeEmbed(ProbeCommand command) {
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("texts", List.of("ping"))));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.TEXT_EMBEDDING);
        return http.postEmbedding(url, command.apiKey(), body, "ok");
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
     * 视觉连通测：兼容模式走 OpenAI 多模态 chat/completions；
     * 原生基址走 multimodal-generation（content 用 {@code image} + {@code text}）。
     * 两条路都带 1x1 图片，禁止退化成纯文本 ping。
     *
     * @param command    出站参数
     * @param compatible 基址是否为兼容模式
     * @return 探活结果
     */
    private ProbeResult vision(ProbeCommand command, boolean compatible) {
        if (compatible) {
            Map<String, Object> userMessage = Map.of(
                    "role", "user",
                    "content", VisionProbePayload.openAiContent());
            String body = http.json(Map.of(
                    "model", command.modelId(),
                    "messages", List.of(userMessage),
                    "max_tokens", 16));
            return http.postJson(http.join(command.baseUrl(), "/chat/completions"),
                    command.apiKey(), body, "视觉连通");
        }
        // 原生 multimodal-generation：messages 放在 input 里，图片与文字是 content 数组的两个块
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", VisionProbePayload.dashScopeNativeContent());
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("messages", List.of(userMessage)),
                "parameters", Map.of("max_tokens", 16)));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.MULTIMODAL_GENERATION);
        return http.postJson(url, command.apiKey(), body, "视觉连通");
    }

    /**
     * 安全护栏探活：DashScope 没有与 OpenAI /moderations 对等的独立端点，
     * 这里按官方文档化的聊天协议发一条固定分类提示，只验证「鉴权 + 模型 + 端点」可达。
     * <p>
     * 本仓对话链路尚无拦截点，成功文案明确写「仅连通性」，不冒充已上线内容审查。
     *
     * @param command    出站参数
     * @param compatible 基址是否为兼容模式
     * @return 探活结果
     */
    private ProbeResult guardrail(ProbeCommand command, boolean compatible) {
        // 固定短句，不发送任何用户内容；模型名由实例配置决定（如审核类模型）
        String prompt = "请判断以下内容是否安全，只回答 SAFE：ping";
        if (compatible) {
            String body = http.json(Map.of(
                    "model", command.modelId(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 8));
            return http.postJson(http.join(command.baseUrl(), "/chat/completions"),
                    command.apiKey(), body, "安全护栏连通（仅连通性，未接入运行时拦截）");
        }
        String body = http.json(Map.of(
                "model", command.modelId(),
                "input", Map.of("messages", List.of(Map.of("role", "user", "content", prompt))),
                "parameters", Map.of("max_tokens", 8, "result_format", "message")));
        String url = DashScopeNativePaths.join(command.baseUrl(), DashScopeNativePaths.TEXT_GENERATION);
        return http.postJson(url, command.apiKey(), body, "安全护栏连通（仅连通性，未接入运行时拦截）");
    }
}
