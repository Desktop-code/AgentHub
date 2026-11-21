package com.agenthub.rag.application;

/**
 * 当前工具调用所属的 Agent 与计量口径。所属域：rag。
 * <p>
 * 为什么需要：RAG 检索 Tool 必须知道「是谁在查」，才能取到该 Agent 绑定的知识库；
 * 查询改写还要按本轮的会话与操作者记账（Token 流水、Trace），这两项也只有运行线程才知道。
 * AgentScope 的 {@code @Tool} 方法签名里没有这些值，因此由 runtime 写入本线程上下文。
 * <p>
 * 线程约束：AgentScope 工具默认在 {@code boundedElastic} 执行，主线程 {@link #bind} 的值到回调时
 * <b>不可见</b>；试跑须经 {@code ToolkitRagContext} 在工具线程重绑。主线程与工具线程均须
 * {@link #clear()}，避免池线程串身份。未设置 Agent 时 Tool 拒绝检索（宁可不查，也不跨 Agent 串库）；
 * 会话 / 操作者缺失时只影响计量维度（按空会话、空用户记），不至于把检索打失败。
 */
public final class RagToolContext {

    /**
     * 一次工具调用的身份与计量维度。所属域：rag。
     *
     * @param agentId    当前 Agent；非空才允许检索
     * @param sessionId  本轮运行会话；可空，仅用于计量与 Trace 关联
     * @param operatorId 平台用户；可空，仅用于流水 created_by
     */
    public record Identity(Long agentId, Long sessionId, Long operatorId) {
    }

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private RagToolContext() {
    }

    /**
     * 只绑 Agent：计量维度留空，供无需记账的调用方与单测使用。
     *
     * @param agentId 当前 Agent；null 等价于清除
     */
    public static void bind(Long agentId) {
        bind(agentId, null, null);
    }

    /**
     * 绑定完整的工具线程身份。
     *
     * @param agentId    当前 Agent；null 等价于清除
     * @param sessionId  本轮运行会话，可空
     * @param operatorId 平台用户，可空
     */
    public static void bind(Long agentId, Long sessionId, Long operatorId) {
        if (agentId == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new Identity(agentId, sessionId, operatorId));
    }

    /**
     * @return 当前 Agent id；未绑定时为 null
     */
    public static Long agentIdOrNull() {
        Identity identity = CURRENT.get();
        return identity == null ? null : identity.agentId();
    }

    /**
     * @return 本轮运行会话 id；未绑定或调用方未提供时为 null
     */
    public static Long sessionIdOrNull() {
        Identity identity = CURRENT.get();
        return identity == null ? null : identity.sessionId();
    }

    /**
     * @return 平台用户 id；未绑定或调用方未提供时为 null
     */
    public static Long operatorIdOrNull() {
        Identity identity = CURRENT.get();
        return identity == null ? null : identity.operatorId();
    }

    /**
     * 本轮结束必须调用，防止线程复用串身份。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
