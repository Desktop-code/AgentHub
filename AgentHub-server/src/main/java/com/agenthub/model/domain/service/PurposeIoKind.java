package com.agenthub.model.domain.service;

/**
 * 协议×用途出站形态。所属域：model。
 * 连通测与后续调用共用此枚举，禁止未适配格静默走 Chat。
 */
public enum PurposeIoKind {

    /** 对话补全类：chat/completions、DashScope generation、Anthropic messages */
    CHAT,
    /** 向量嵌入 */
    EMBEDDING,
    /** 文本重排 */
    RERANK,
    /**
     * 视觉：三协议均按各自多模态报文带图片块，不得退化成纯文本 ping 冒充多模态可用。
     */
    VISION,
    /**
     * 语音识别：兼容模式 multipart 上传整段音频走 /audio/transcriptions；
     * DashScope 原生基址走 multimodal-generation（qwen3-asr-flash 同步，音频以 data URL 传入）。
     * 禁止用 Chat 路径冒充识别。
     */
    SPEECH_TO_TEXT,
    /**
     * 语音合成：提交整段文字，走 /audio/speech 收音频字节。
     * OPENAI_COMPAT 与 DashScope 兼容模式走 /audio/speech；
     * DashScope 原生基址走 /services/audio/tts/SpeechSynthesizer。
     * 禁止用 Chat 路径冒充合成。
     */
    TEXT_TO_SPEECH,
    /**
     * 安全护栏探活：只验证该协议的安全 / 审核口（或文档化的分类提示）是否可达。
     * 本期<b>不</b>代表运行时已在对话链路做内容审查；禁止把探活成功当成拦截能力已上线。
     */
    GUARDRAIL,
    /** 本期未接专用路径；连通测与出站必须明确失败，禁冒充 Chat */
    UNSUPPORTED
}
