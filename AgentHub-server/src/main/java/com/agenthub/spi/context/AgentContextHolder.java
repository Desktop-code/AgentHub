package com.agenthub.spi.context;

/**
 * 终端用户上下文的唯一出入口。所属域：spi。
 * <p>调用方：以后的网关过滤器或 Agent 运行时。本期没有过滤器写入。
 * 实现方：必须把线程存储收口在实现类内部。
 * 线程：set 与 clear 必须成对，请求结束时清理，避免线程池串数据。
 */
public interface AgentContextHolder {

    /**
     * 绑定当前线程的终端上下文。
     *
     * @param context 允许为 null，等价于 clear
     */
    void set(AgentContext context);

    /**
     * @return 当前线程的上下文；未设置时为 null
     */
    AgentContext get();

    /**
     * 请求结束时调用，防止线程复用串租户。
     */
    void clear();
}
