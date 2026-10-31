package com.agenthub.spi.context;

import org.springframework.stereotype.Component;

/**
 * 基于 ThreadLocal 的上下文持有者。所属域：spi。
 * 约束：全进程只有这里能 new ThreadLocal&lt;AgentContext&gt;；本期没有调用方写入，持有者本身可用。
 */
@Component
public class ThreadLocalAgentContextHolder implements AgentContextHolder {

    private final ThreadLocal<AgentContext> local = new ThreadLocal<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(AgentContext context) {
        if (context == null) {
            local.remove();
            return;
        }
        local.set(context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AgentContext get() {
        return local.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        local.remove();
    }
}
