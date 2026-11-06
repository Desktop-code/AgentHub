package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;

/**
 * 协议×用途连通测 / 出站总闸。所属域：model。
 * <p>
 * 各协议客户端与 RAG Embedding / Rerank 出站必须经此矩阵分流；未适配格返回明确文案，
 * <b>禁止</b>静默用 Chat 路径测 Rerank / Embedding；视觉走图文混排（三协议均按各自多模态报文）；
 * 语音识别 / 合成走专用音频端点（兼容模式 {@code /audio/transcriptions}、{@code /audio/speech}；
 * DashScope 原生 {@code multimodal-generation}、{@code SpeechSynthesizer}），
 * 同样禁止用 Chat 冒充——那头只会回文字，前端拿不到可播放的音频。
 * GUARDRAIL 只做「该协议审核口可达」的探活，<b>不</b>表示运行时会拦截对话内容。
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
        if (capability.usesSpeechToText()) {
            return speechToText(protocol, capability);
        }
        if (capability.usesTextToSpeech()) {
            return textToSpeech(protocol, capability);
        }
        if (capability == ModelCapability.GUARDRAIL) {
            return guardrail(protocol, capability);
        }
        return PurposeRoute.unsupported(protocol, capability, "该协议没有对应端点，禁止冒充 Chat");
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
                    "DashScope embedding（compatible-mode /embeddings 或原生 text-embedding）");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未提供 Embedding 端点");
            default -> PurposeRoute.unsupported(protocol, capability, "该协议没有 Embedding 端点");
        };
    }

    private static PurposeRoute rerank(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.RERANK,
                    "DashScope text-rerank（原生 /services/rerank/text-rerank）");
            case OPENAI_COMPAT -> PurposeRoute.unsupported(protocol, capability,
                    "OpenAI 兼容协议没有官方 /rerank 端点");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未提供 Rerank 端点");
            default -> PurposeRoute.unsupported(protocol, capability, "该协议没有 Rerank 端点");
        };
    }

    /**
     * 视觉：三协议凡支持多模态聊天的，都按「图像 + 文本」探活。
     * OPENAI_COMPAT 走 chat/completions + image_url；
     * DashScope 兼容模式走 chat/completions，原生基址走 multimodal-generation；
     * Anthropic 走 messages + image source（base64）。
     * 不支持的协议明确未适配，禁止退化成纯文本 ping。
     */
    private static PurposeRoute vision(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.VISION,
                    "OpenAI 兼容多模态 chat/completions（1x1 图片）");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.VISION,
                    "DashScope 兼容模式 chat/completions 或原生 multimodal-generation（1x1 图片）");
            case ANTHROPIC -> PurposeRoute.of(protocol, capability, PurposeIoKind.VISION,
                    "Anthropic messages 多模态 image source（1x1 图片）");
            default -> PurposeRoute.unsupported(protocol, capability, "该协议没有视觉探活路径");
        };
    }

    /**
     * 语音识别：兼容模式走 {@code /audio/transcriptions}（multipart）。
     * DashScope 原生基址走 {@code multimodal-generation}（qwen3-asr-flash 同步，音频以 data URL 传入）；
     * Anthropic 没有语音端点，明确未适配。
     */
    private static PurposeRoute speechToText(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.SPEECH_TO_TEXT,
                    "OpenAI 兼容 /audio/transcriptions（multipart 整段音频）");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.SPEECH_TO_TEXT,
                    "DashScope 兼容模式 /audio/transcriptions 或原生 qwen3-asr-flash multimodal-generation");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未提供语音识别端点");
            default -> PurposeRoute.unsupported(protocol, capability, "该协议没有语音识别端点");
        };
    }

    /**
     * 语音合成：兼容模式走 {@code /audio/speech}（JSON 进、音频字节出）；
     * DashScope 原生基址走 {@code /services/audio/tts/SpeechSynthesizer}
     * （返回 audio.data base64 或 audio.url，由适配器下载成字节）。
     * 禁止用 chat/completions 冒充合成——那头只会返回文字，前端拿到也无法播放。
     */
    private static PurposeRoute textToSpeech(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.TEXT_TO_SPEECH,
                    "OpenAI 兼容 /audio/speech（整段文字 → 音频）");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.TEXT_TO_SPEECH,
                    "DashScope 兼容模式 /audio/speech 或原生 SpeechSynthesizer");
            case ANTHROPIC -> PurposeRoute.unsupported(protocol, capability, "Anthropic 未提供语音合成端点");
            default -> PurposeRoute.unsupported(protocol, capability, "该协议没有语音合成端点");
        };
    }

    /**
     * 安全护栏：只探活「该协议审核口可达」，不代表运行时已做内容审查。
     * OPENAI_COMPAT 打官方 {@code /moderations}；
     * DashScope 按聊天协议探活（可配 qwen-guard 等审核模型）；
     * Anthropic 无独立审核端点，按官方文档化的 Messages 分类提示探活。
     */
    private static PurposeRoute guardrail(ModelProtocol protocol, ModelCapability capability) {
        return switch (protocol) {
            case OPENAI_COMPAT -> PurposeRoute.of(protocol, capability, PurposeIoKind.GUARDRAIL,
                    "OpenAI 兼容 /moderations 安全审核探活（仅连通性，未接入运行时拦截）");
            case DASHSCOPE -> PurposeRoute.of(protocol, capability, PurposeIoKind.GUARDRAIL,
                    "DashScope 安全护栏（按聊天协议探活，仅连通性，未接入运行时拦截）");
            case ANTHROPIC -> PurposeRoute.of(protocol, capability, PurposeIoKind.GUARDRAIL,
                    "Anthropic 安全护栏（按 Messages 分类提示探活，仅连通性，未接入运行时拦截）");
            default -> PurposeRoute.unsupported(protocol, capability, "该协议没有安全护栏探活路径");
        };
    }
}
