package com.agenthub.runtime.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作台「手动换线」一次性强制标记的口径守卫。所属域：runtime 的 application。
 *
 * <p>为什么值得单独测：这枚标记决定「下一句是不是被钉给某位专家」。
 * 用户 @ 之后点 × 取消，如果标记没被丢掉，下一句新问题就会被悄悄钉走——
 * 页面却写着「自动匹配专家」，两边口径相反。
 */
class WorkbenchRouteForceRegistryTest {

    @Test
    void takeIsConsuming() {
        WorkbenchRouteForceRegistry registry = new WorkbenchRouteForceRegistry();
        registry.put(1L, "billing");
        assertEquals("billing", registry.take(1L).orElseThrow());
        // 一次选择只作用一轮：再取必须为空，不能反复生效
        assertTrue(registry.take(1L).isEmpty());
    }

    @Test
    void clearDropsUnconsumedForce() {
        WorkbenchRouteForceRegistry registry = new WorkbenchRouteForceRegistry();
        registry.put(2L, "backend_expert");
        // 用户在提及片上点 × 取消：强制标记必须一起丢掉，下一句才会回到入口判定
        registry.clear(2L);
        assertTrue(registry.take(2L).isEmpty(), "取消后不得再强制走原线路");
    }

    @Test
    void blankKeysAndNullSessionsAreIgnored() {
        WorkbenchRouteForceRegistry registry = new WorkbenchRouteForceRegistry();
        registry.put(3L, "   ");
        registry.put(null, "billing");
        registry.put(3L, null);
        assertTrue(registry.take(3L).isEmpty());
    }

    @Test
    void lastWriteWins() {
        WorkbenchRouteForceRegistry registry = new WorkbenchRouteForceRegistry();
        registry.put(4L, "front");
        registry.put(4L, "backend");
        assertEquals("backend", registry.take(4L).orElseThrow());
    }
}
