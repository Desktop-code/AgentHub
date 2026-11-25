package com.agenthub.runtime.application;

import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空闲到点先收口 SUCCESS，再批抽；单轮有 N 上限。未空闲或本轮在跑的不抽。
 */
@ExtendWith(MockitoExtension.class)
class TrialSessionCloserTest {

    @Mock
    private RunSessionRepository sessions;
    @Mock
    private MemoryPolicyAppService policies;
    @Mock
    private TrialFlight flight;
    @Mock
    private TrialObsHooks obs;
    @Mock
    private TrialMemoryCoordinator memory;

    private MemoryProperties memoryProperties;
    private TrialSessionCloser closer;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        memoryProperties.setSessionCloseBatchLimit(5);
        memoryProperties.setLongExtractIntervalMs(0L);
        closer = new TrialSessionCloser(sessions, policies, memoryProperties, flight, obs, memory);
    }

    /**
     * 已空闲：先写 SUCCESS + ended_at，再 onClosed；抽取失败也不回滚（本测只验证终态写入顺序）。
     */
    @Test
    void idleSweepClosesThenExtracts() {
        RunSession stale = running(9L, LocalDateTime.now().minusSeconds(200));
        when(policies.effective(4L)).thenReturn(policy(100));
        when(sessions.listRunning(null, null)).thenReturn(List.of(stale));
        when(flight.running(9L)).thenReturn(false);
        closer.sweep();
        assertEquals("SUCCESS", stale.getStatus());
        assertNotNull(stale.getEndTime());
        verify(sessions).updateOutcome(stale, 0L);
        verify(memory).onClosed(stale);
        verify(memory, never()).onIdleExtract(any());
        verify(obs, never()).cancel(anyLong());
    }

    @Test
    void freshOrInFlightDoesNotExtract() {
        RunSession fresh = running(9L, LocalDateTime.now());
        RunSession busy = running(10L, LocalDateTime.now().minusHours(5));
        when(policies.effective(4L)).thenReturn(policy(100));
        when(sessions.listRunning(null, null)).thenReturn(List.of(fresh, busy));
        when(flight.running(9L)).thenReturn(false);
        when(flight.running(10L)).thenReturn(true);
        closer.sweep();
        verify(memory, never()).onIdleExtract(any());
        verify(memory, never()).onClosed(any());
        verify(sessions, never()).updateOutcome(any(), any());
    }

    /**
     * 单轮最多处理 N 个空闲会话，其余留待下一轮。
     */
    @Test
    void sweepRespectsBatchLimit() {
        memoryProperties.setSessionCloseBatchLimit(2);
        List<RunSession> idle = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            idle.add(running(id, LocalDateTime.now().minusHours(3)));
        }
        when(policies.effective(4L)).thenReturn(policy(100));
        when(sessions.listRunning(null, null)).thenReturn(idle);
        when(flight.running(anyLong())).thenReturn(false);
        closer.sweep();
        verify(memory, times(2)).onClosed(any());
        verify(sessions, times(2)).updateOutcome(any(), any());
        assertEquals("SUCCESS", idle.get(0).getStatus());
        assertEquals("SUCCESS", idle.get(1).getStatus());
        assertEquals("RUNNING", idle.get(2).getStatus());
        assertNull(idle.get(2).getEndTime());
    }

    /**
     * 取消仍写 CANCELLED + ended_at，并走 onClosed 抽。
     */
    @Test
    void cancelMarksCancelledAndExtracts() {
        RunSession session = running(12L, LocalDateTime.now());
        when(flight.running(12L)).thenReturn(false);
        closer.cancel(session, 7L);
        assertEquals("CANCELLED", session.getStatus());
        assertEquals("已取消", session.getErrorSummary());
        verify(sessions).updateOutcome(session, 7L);
        verify(obs).cancel(12L);
        verify(memory).onClosed(session);
        verify(memory, never()).onIdleExtract(any());
    }

    /**
     * 删除前批抽：RUNNING 且本轮未在跑才 onClosed；不改会话行。
     */
    @Test
    void extractBeforeDeleteCallsOnClosedForRunning() {
        RunSession session = running(15L, LocalDateTime.now());
        when(flight.running(15L)).thenReturn(false);
        closer.extractBeforeDelete(session);
        verify(memory).onClosed(session);
        verify(sessions, never()).updateOutcome(any(), any());
        assertEquals("RUNNING", session.getStatus());
        assertNull(session.getEndTime());
    }

    @Test
    void extractBeforeDeleteSkipsBusyOrClosed() {
        RunSession busy = running(16L, LocalDateTime.now());
        when(flight.running(16L)).thenReturn(true);
        closer.extractBeforeDelete(busy);
        verify(memory, never()).onClosed(any());

        RunSession closed = running(17L, LocalDateTime.now());
        closed.setStatus("CANCELLED");
        closer.extractBeforeDelete(closed);
        verify(memory, never()).onClosed(any());
    }

    /**
     * 到期判定必须读策略里的空闲秒数：策略 60 秒 + 会话已空闲 90 秒 → 必须收口。
     * 若调度侧写死 7200，本用例会失败，这就是「不写死默认值」的回归保护。
     */
    @Test
    void sweepUsesPolicyIdleSeconds() {
        RunSession session = running(20L, LocalDateTime.now().minusSeconds(90));
        when(policies.effective(4L)).thenReturn(policy(60));
        when(sessions.listRunning(null, null)).thenReturn(List.of(session));
        when(flight.running(20L)).thenReturn(false);

        closer.sweep();

        assertEquals("SUCCESS", session.getStatus());
        verify(memory).onClosed(session);
    }

    /**
     * 同一会话在策略空闲 120 秒时只过了 90 秒：不到期，不该收口也不该批抽。
     */
    @Test
    void sweepHonoursLongerPolicyIdle() {
        RunSession session = running(21L, LocalDateTime.now().minusSeconds(90));
        when(policies.effective(4L)).thenReturn(policy(120));
        when(sessions.listRunning(null, null)).thenReturn(List.of(session));
        when(flight.running(21L)).thenReturn(false);

        closer.sweep();

        assertEquals("RUNNING", session.getStatus());
        verify(memory, never()).onClosed(any());
    }

    private static RunSession running(long id, LocalDateTime updateTime) {
        RunSession session = new RunSession();
        session.setId(id);
        session.setAgentId(4L);
        session.setUserId(7L);
        session.setStatus("RUNNING");
        session.setUpdateTime(updateTime);
        return session;
    }

    private static MemoryPolicy policy(int idleSec) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setCtxIdleTtlSec(idleSec);
        return policy;
    }
}
