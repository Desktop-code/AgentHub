package com.agenthub.agent.domain.model;

/**
 * Router 多子终答模式。所属域：agent。
 * 默认 CONCAT；SUMMARIZE 才再调父模型汇总一轮。
 */
public enum RouterAnswerMode {

    /** 按判路由顺序拼接各子终答，不再调汇总 LLM */
    CONCAT,

    /** 各子结束后用父 Router 文本模型做一轮汇总 */
    SUMMARIZE;

    /**
     * 解析配置值；空或未知回落 CONCAT。
     *
     * @param raw 配置原文
     * @return 枚举
     */
    public static RouterAnswerMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONCAT;
        }
        try {
            return RouterAnswerMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CONCAT;
        }
    }
}
