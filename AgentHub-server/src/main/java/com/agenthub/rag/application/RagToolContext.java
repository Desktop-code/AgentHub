package com.agenthub.rag.application;

/**
 * 当前工具调用所属的 Agent。所属域：rag。
 * <p>
 * 为什么需要：RAG 检索 Tool 必须知道「是谁在查」，才能取到该 Agent 绑定的知识库。
 * AgentScope 的 {@code @Tool} 方法签名里没有 agentId，因此由 runtime 写入本线程上下文。
 * <p>
 * 线程约束：AgentScope 工具默认在 {@code boundedElastic} 执行，主线程 {@link #bind} 的值到回调时
 * <b>不可见</b>；试跑须经 {@code ToolkitRagContext} 在工具线程重绑。主线程与工具线程均须
 * {@link #clear()}，避免池线程串身份。未设置时 Tool 拒绝检索（宁可不查，也不跨 Agent 串库）。
 */
public final class RagToolContext {

    private static final ThreadLocal<Long> AGENT_ID = new ThreadLocal<>();

    private RagToolContext() {
    }

    /**
     * @param agentId 当前 Agent；null 等价于清除
     */
    public static void bind(Long agentId) {
        if (agentId == null) {
            AGENT_ID.remove();
            return;
        }
        AGENT_ID.set(agentId);
    }

    /**
     * @return 当前 Agent id；未绑定时为 null
     */
    public static Long agentIdOrNull() {
        return AGENT_ID.get();
    }

    /**
     * 本轮结束必须调用，防止线程复用串身份。
     */
    public static void clear() {
        AGENT_ID.remove();
    }
}
