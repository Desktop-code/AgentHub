package com.agenthub.agent.domain.model;

/**
 * 定义和绑定行的启停。所属域：agent。与库注释一致：1 正常，0 停用。
 */
public final class AgentStatus {

    /** 正常，允许被新的绑定引用 */
    public static final int ENABLED = 1;
    /** 停用，已有绑定可以保留，但不能重新启用到一个停用目标上 */
    public static final int DISABLED = 0;

    private AgentStatus() {
    }
}
