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
     * 视觉：与 Chat 同打 chat/completions，但消息体必须带内联图片块。
     * 当前仅 OPENAI_COMPAT 与 DashScope 兼容模式开放；不得退化成纯文本 ping 冒充多模态可用。
     */
    VISION,
    /** 本期未接专用路径；连通测与出站必须明确失败，禁冒充 Chat */
    UNSUPPORTED
}
