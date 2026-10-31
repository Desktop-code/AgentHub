package com.agenthub.model.domain.model;

/**
 * 供应商协议。所属域：model。入库值为枚举名，后续可追加常量。
 * 连通测 / 出站按协议×用途矩阵分流（见 ModelPurposeMatrix）；CUSTOM 仅 HTTP 可达。
 */
public enum ModelProtocol {

    /** OpenAI 兼容，含多数中转、vLLM 和本地网关 */
    OPENAI_COMPAT,
    /** 阿里云灵积。兼容模式与原生接口由适配器按 baseUrl 区分 */
    DASHSCOPE,
    /** Anthropic Messages。本期不接完整对话 */
    ANTHROPIC,
    /** 自定义地址，只检查 HTTP 是否可达 */
    CUSTOM
}
