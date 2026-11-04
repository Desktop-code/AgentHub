package com.agenthub.obs.domain.model;

/**
 * 无对话会话场景（工作流 run）的 obs_trace 占位 id。所属域：obs。
 *
 * <p>为什么需要占位：{@code obs_trace.session_id} / {@code user_id} 在现网表里都是 NOT NULL，
 * 而工作流 run 没有 {@code agent_run_session}，也没有必然存在的平台用户。
 * 本刀不改列（禁 DROP / 禁重建），统一写「约定占位值」并在交付文档里说明：
 * 左栏会话列表天然查不到它们，工作流 trace 只能通过 workflowId 过滤或 traceId 直达。
 *
 * <p>占位值只用于「没有真实值」的场景，绝不做业务关联：不要拿它去 join 任何业务表。
 */
public final class ObsPlaceholders {

    /** session_id 占位：0 表示「非对话会话」 */
    public static final long SESSION_NONE = 0L;

    /** user_id 占位：0 表示「无平台用户」（如应用触发的工作流 run） */
    public static final long SYSTEM_USER_ID = 0L;

    private ObsPlaceholders() {
    }
}
