package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.ContextTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触顶必须切出旧段，不能把整窗直接丢掉。
 */
class ContextWindowPlannerTest {

    @Test
    void underLimitKeepsEverything() {
        ContextWindowPlanner.Decision decision = ContextWindowPlanner.decide(turns(3), 5, 1000);
        assertFalse(decision.overflow());
        assertEquals(3, decision.tail().size());
    }

    @Test
    void tooManyTurnsMovesOldestIntoHead() {
        ContextWindowPlanner.Decision decision = ContextWindowPlanner.decide(turns(5), 3, 1000);
        assertTrue(decision.overflow());
        assertEquals(2, decision.head().size());
        assertEquals("t1", decision.head().get(0).getText());
        assertEquals(3, decision.tail().size());
        assertEquals("t3", decision.tail().get(0).getText());
    }

    @Test
    void charLimitKeepsSuffixOfTheOnlyOversizedTurn() {
        ContextTurn turn = ContextTurn.of("user", "abcdefghij");
        ContextWindowPlanner.Decision decision = ContextWindowPlanner.decide(List.of(turn), 10, 4);
        assertTrue(decision.overflow());
        assertEquals("abcdef", decision.head().get(0).getText());
        assertEquals("ghij", decision.tail().get(0).getText());
    }

    private static List<ContextTurn> turns(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> ContextTurn.of("user", "t" + i))
                .toList();
    }
}
