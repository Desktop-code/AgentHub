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
    /** 本期未接专用路径；连通测与出站必须明确失败，禁冒充 Chat */
    UNSUPPORTED
}
