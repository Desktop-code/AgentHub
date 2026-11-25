package com.agenthub.runtime.domain.model;

import com.agenthub.billing.domain.model.MeterCommand;
import com.agenthub.runtime.domain.service.MeterTurnHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计量范围的账本去重键来源。所属域：runtime。
 * <p>
 * 证明「写入路径」真的会把业务唯一键带给 billing：轮内每条流水序号自增，
 * 轮外（未设置 / 已清理）不产生键，从而退化为普通记账而不是错杀正常账。
 */
class MeterScopeDedupeKeyTest {

    private static final long SESSION_ID = 7L;
    private static final String TURN_KEY = "idem-key-0001";

    @AfterEach
    void tearDown() {
        // ThreadLocal 必须清干净，避免污染同包其它用例
        MeterTurnHolder.clear();
    }

    @Test
    void turnKeyProducesIncrementingDedupeKeys() {
        MeterTurnHolder.begin(TURN_KEY);

        MeterCommand first = MeterScope.console(9L, 3L, SESSION_ID, 100L).command(10, 5, null);
        MeterCommand second = MeterScope.console(9L, 3L, SESSION_ID, 100L).command(20, 8, null);

        assertEquals("v1:" + SESSION_ID + ":" + TURN_KEY + ":1", first.getDedupeKey());
        assertEquals("v1:" + SESSION_ID + ":" + TURN_KEY + ":2", second.getDedupeKey());
        // 同一轮内两条不同调用绝不能撞键，否则会错杀 Normal 账
        assertNotEquals(first.getDedupeKey(), second.getDedupeKey());
    }

    @Test
    void noTurnKeyKeepsPlainRecord() {
        MeterCommand command = MeterScope.console(9L, 3L, SESSION_ID, 100L).command(10, 5, null);
        assertNull(command.getDedupeKey());
    }

    @Test
    void clearedTurnStopsProducingKeys() {
        MeterTurnHolder.begin(TURN_KEY);
        MeterTurnHolder.clear();

        MeterCommand command = MeterScope.console(9L, 3L, SESSION_ID, 100L).command(10, 5, null);
        assertNull(command.getDedupeKey());
    }

    @Test
    void blankTurnKeyIsTreatedAsNoTurn() {
        MeterTurnHolder.begin("   ");
        assertNull(MeterTurnHolder.current());
        assertNull(MeterScope.console(9L, 3L, SESSION_ID, 100L).command(1, 1, null).getDedupeKey());
    }

    @Test
    void scopeKeepsTurnReferenceAfterThreadLocalCleared() {
        // 抓的是引用：记账发生在模型流结束（可能已换线程 / 已清上下文），仍要算得出同一个键
        MeterTurnHolder.begin(TURN_KEY);
        MeterScope scope = MeterScope.console(9L, 3L, SESSION_ID, 100L);
        MeterTurnHolder.clear();

        MeterCommand command = scope.command(10, 5, null);
        assertTrue(command.getDedupeKey().endsWith(":" + TURN_KEY + ":1"));
    }
}
