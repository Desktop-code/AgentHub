package com.agenthub.runtime.domain.model;

/**
 * 会话标题来源。所属域：runtime。对应 agent_run_session.title_source。
 * <p>约束：{@link #MANUAL} 一旦写入，异步自动生成不得再覆盖；自动生成只覆盖 {@link #AUTO} 或未标注的行。
 */
public enum TitleSource {

    /** 平台按首轮对话自动生成（模型概括，≤10 汉字） */
    AUTO,

    /** 用户在左栏手动改名；此后自动生成不再覆盖 */
    MANUAL
}
