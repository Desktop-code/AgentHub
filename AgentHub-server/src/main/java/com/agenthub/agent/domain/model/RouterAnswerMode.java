package com.agenthub.agent.domain.model;

/**
 * Router 多子终答模式。所属域：agent。
 * 默认 CONCAT；SUMMARIZE 才再调父模型汇总一轮；VOTE 由评选模型在多份子终答里投票选一路。
 */
public enum RouterAnswerMode {

    /** 按判路由顺序拼接各子终答，不再调汇总 LLM */
    CONCAT,

    /** 各子结束后用父 Router 文本模型做一轮汇总 */
    SUMMARIZE,

    /**
     * 投票选优：各成功子终答进票池，评选模型选一路原文作为终答，不做二次改写。
     * <p>票池不足 / 无合法赢家 / 未配评选模型 / 调用失败一律降级 CONCAT（不是 COMPETE 的 FIRST_SUCCESS）。
     */
    VOTE;

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
