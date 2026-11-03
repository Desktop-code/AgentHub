package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;

/**
 * 协议×用途连通测 / 出站总闸。所属域：model。
 * <p>
 * 各协议客户端与 RAG Rerank 出站必须经此矩阵分流；未适配格返回明确文案，
 * <b>禁止</b>静默用 Chat 路径测 Rerank / Embedding；视觉走图文混排（OPENAI_COMPAT 与 DashScope 兼容模式）。
 * CUSTOM 不在业务矩阵内（仅 HTTP 可达），调用方自行处理。
 */
public final class ModelPurposeMatrix {

    private ModelPurposeMatrix() {
    }

    /**
     * 解析协议×用途对应的出站形态。
     *
     * @param protocol   供应商协议；null 视为未适配
     * @param capability 模型用途；null 视为未适配
     * @return 路由；adapted=false 时不得发 Chat/专用业务请求
     */
    public static PurposeRoute resolve(ModelProtocol protocol, ModelCapability capability) {
        if (protocol == null || capability == null) {
            return PurposeRoute.unsupported(
                    protocol == null ? ModelProtocol.CUSTOM : protocol,
                    capability == null ? ModelCapability.CHAT : capability,
                    "协议或用途为空");
        }
        if (protocol == ModelProtocol.CUSTOM) {
            return PurposeRoute.unsupported(protocol, capability, "CUSTOM 仅做 HTTP 可达，无按用途专用路径");
        }
        if (capability.usesChat()) {
            return chat(protocol, capability);
        }
        if (capability.usesEmbedding()) {
            return embedding(protocol, capability);
        }
        if (capability.usesRerank()) {
            return rerank(protocol, capability);
        }
        if (capability.usesVision()) {
            return vision(protocol, capability);
        }
        // ASR / TTS / GUARDRAIL：可配，但本期不真出站冒充成功
        return PurposeRoute.unsupported(protocol, capability, "本期未接专用连通测与出站，禁止冒充 Chat");
    }

    private static PurposeRoute chat(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.CHAT,
                    "OpenAI 兼容 chat/completions");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.CHAT,
                    "DashScope Chat（compatible-mode 或原生 generation）");
            case ANTHROPIC -> PurposeRoute.of(protocol, capability, PurposeIoKind.CHAT,
                    "Anthropic messages");
            default -> PurposeRoute.unsupported(protocol, capability, "无 Chat 路径");
        };
    }

    private static PurposeRoute embedding(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.EMBEDDING,
                    "OpenAI 兼容 /embeddings");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.EMBEDDING,
                    "DashScope embedding（compatible-mode 或原生 text-embedding）");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未接 Embedding");
            default -> PurposeRoute.unsupported(protocol, capability, "无 Embedding 路径");
        };
    }

    private static PurposeRoute rerank(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.RERANK,
                    "DashScope text-rerank");
            case OPENAI_COMPAT -> PurposeRoute.unsupported(protocol, capability,
                    "未约定 OpenAI 兼容 rerank 路径");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未接 Rerank");
            default -> PurposeRoute.unsupported(protocol, capability, "无 Rerank 路径");
        };
    }

    /**
     * 视觉：按 OpenAI 多模态 Chat 协议探活（chat/completions + image_url 内联图片）。
     * DashScope 只有 compatible-mode 基址才是该形态，因此矩阵放行、由客户端按 baseUrl 二次判定；
     * 原生 generation 端点报文不同，仍拒绝，避免用未验证报文冒充可用。
     */
    private static PurposeRoute vision(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.VISION,
                    "OpenAI 兼容多模态 chat/completions（1x1 图片）");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.VISION,
                    "DashScope 兼容模式多模态 chat/completions（1x1 图片，需 compatible-mode 基址）");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未接视觉探活");
            default -> PurposeRoute.unsupported(protocol, capability, "无视觉探活路径");
        };
    }
}
