package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.RunSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲批抽只看仍在进行、没有在跑、并且超过策略空闲秒数的会话。
 */
class SessionIdleTest {

    @Test
    void exactIdleIsDueButFreshOrBusyIsNot() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        RunSession session = running(now.minusSeconds(100));
        assertTrue(SessionIdle.due(session, 100, now, false));
        assertFalse(SessionIdle.due(session, 100, now, true));
        session.setUpdateTime(now.minusSeconds(99));
        assertFalse(SessionIdle.due(session, 100, now, false));
        session.setStatus("SUCCESS");
        session.setUpdateTime(now.minusHours(3));
        assertFalse(SessionIdle.due(session, 100, now, false));
    }

    private static RunSession running(LocalDateTime updateTime) {
        RunSession session = new RunSession();
        session.setStatus("RUNNING");
        session.setUpdateTime(updateTime);
        return session;
    }
}
