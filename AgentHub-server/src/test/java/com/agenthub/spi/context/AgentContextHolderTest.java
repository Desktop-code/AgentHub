package com.agenthub.spi.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 终端上下文只允许出现在 Holder 里。本期没有过滤器写入，这里确认 set/get/clear 成对。
 */
class AgentContextHolderTest {

    /**
     * 清理后线程上不应再残留上一次的租户。
     */
    @Test
    void setGetAndClear() {
        ThreadLocalAgentContextHolder holder = new ThreadLocalAgentContextHolder();
        AgentContext context = AgentContext.builder().tenant("t1").user("u1").scope("read").build();
        holder.set(context);
        assertEquals("t1", holder.get().getTenant());
        holder.clear();
        assertNull(holder.get());
        holder.set(null);
        assertNull(holder.get());
    }
}
