package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案嵌套深度计数。所属域：runtime。
 *
 * <p>入口 → 方案 → 专家只进入一层，必须仍低于熔断上限。
 */
class MaNestedContextTest {

    @AfterEach
    void clear() {
        while (MaNestedContext.inside()) {
            MaNestedContext.exit();
        }
    }

    @Test
    void entryToSchemeToExpertStaysUnderTheCap() {
        assertEquals(0, MaNestedContext.depth());
        MaNestedContext.enter();
        assertEquals(1, MaNestedContext.depth());
        assertTrue(MaNestedContext.depth() < AgentRuntimeConfig.MAX_MA_NEST_DEPTH);
        MaNestedContext.exit();
        assertFalse(MaNestedContext.inside());
    }

    @Test
    void thirdEnteredSchemeIsTheCap() {
        MaNestedContext.enter();
        MaNestedContext.enter();
        MaNestedContext.enter();
        assertEquals(AgentRuntimeConfig.MAX_MA_NEST_DEPTH, MaNestedContext.depth());
        assertFalse(MaNestedContext.depth() < AgentRuntimeConfig.MAX_MA_NEST_DEPTH);
    }
}
