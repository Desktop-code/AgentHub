package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.MemoryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽取只在显式记住、会话结束和滚动摘要。普通一轮不开。
 */
class LongExtractTriggersTest {

    @Test
    void explicitCanStayOnWhenBatchExtractIsOff() {
        MemoryPolicy policy = enabled();
        policy.setEnableLongExtract(0);
        policy.setLongExtractOnExplicit(1);
        assertTrue(LongExtractTriggers.explicit(policy, "请记住我喜欢简洁"));
        assertFalse(LongExtractTriggers.sessionEnd(policy));
        assertFalse(LongExtractTriggers.onSummary(policy));
        assertFalse(LongExtractTriggers.explicit(policy, "帮我查一下订单"));
    }

    @Test
    void sessionEndNeedsExtractSwitch() {
        MemoryPolicy policy = enabled();
        assertTrue(LongExtractTriggers.sessionEnd(policy));
        assertTrue(LongExtractTriggers.onSummary(policy));
        policy.setLongExtractOnSessionEnd(0);
        assertFalse(LongExtractTriggers.sessionEnd(policy));
    }

    private static MemoryPolicy enabled() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setEnableLongExtract(1);
        policy.setLongExtractOnExplicit(1);
        policy.setLongExtractOnSessionEnd(1);
        return policy;
    }
}
