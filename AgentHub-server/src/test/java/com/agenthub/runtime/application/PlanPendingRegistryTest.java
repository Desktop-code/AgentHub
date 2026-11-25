package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanStep;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待批准计划的登记与消费：只能被取一次，且不跨用户。
 */
class PlanPendingRegistryTest {

    private static PendingPlan plan(Long sessionId, long userId, LocalDateTime createTime) {
        return new PendingPlan("plan-1", sessionId, 9L, userId, "用户原文", "已注入记忆的文本",
                List.of(new PlanStep(1, "一步", "")), createTime);
    }

    @Test
    void takeConsumesOnlyOnce() {
        PlanPendingRegistry registry = new PlanPendingRegistry();
        registry.put(plan(1L, 100L, LocalDateTime.now()));
        Optional<PendingPlan> first = registry.take(1L, 100L);
        assertTrue(first.isPresent());
        assertEquals("已注入记忆的文本", first.get().modelUser());
        // 第二次批准必须落空：否则同一份计划会被执行两遍、计两次费
        assertTrue(registry.take(1L, 100L).isEmpty());
    }

    @Test
    void wrongUserCannotTake() {
        PlanPendingRegistry registry = new PlanPendingRegistry();
        registry.put(plan(2L, 100L, LocalDateTime.now()));
        assertTrue(registry.take(2L, 200L).isEmpty());
        // 非归属用户的尝试会顺带清掉登记项，避免被反复试探
        assertTrue(registry.peek(2L).isEmpty());
    }

    @Test
    void expiredPlanIsInvisible() {
        PlanPendingRegistry registry = new PlanPendingRegistry();
        registry.put(plan(3L, 100L, LocalDateTime.now().minusHours(2)));
        assertTrue(registry.peek(3L).isEmpty());
        assertTrue(registry.take(3L, 100L).isEmpty());
    }

    @Test
    void peekDoesNotConsume() {
        PlanPendingRegistry registry = new PlanPendingRegistry();
        registry.put(plan(4L, 100L, LocalDateTime.now()));
        assertTrue(registry.peek(4L).isPresent());
        assertTrue(registry.peek(4L).isPresent());
        assertTrue(registry.take(4L, 100L).isPresent());
    }

    @Test
    void putReplacesPreviousAndDropRemoves() {
        PlanPendingRegistry registry = new PlanPendingRegistry();
        registry.put(plan(5L, 100L, LocalDateTime.now()));
        registry.put(plan(5L, 100L, LocalDateTime.now()));
        assertTrue(registry.peek(5L).isPresent());
        registry.drop(5L);
        assertFalse(registry.peek(5L).isPresent());
        // 空会话 id 不得抛异常，调用方可能拿到未创建的会话
        registry.put(plan(null, 100L, LocalDateTime.now()));
        assertTrue(registry.peek(null).isEmpty());
    }
}
