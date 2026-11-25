package com.agenthub.memory.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 旧四级顺序工具仍保留给兼容调用；业务已改走 PurposeModelResolver。
 */
class SummaryModelOrderTest {

    @Test
    void skipsUnusableAndUsesYamlLast() {
        assertEquals(2L, SummaryModelOrder.first(9L, 2L, 3L, 4L, id -> id == 2L));
        assertEquals(4L, SummaryModelOrder.first(null, null, null, 4L, id -> id == 4L));
        assertNull(SummaryModelOrder.first(1L, 2L, 3L, 4L, id -> false));
    }
}
