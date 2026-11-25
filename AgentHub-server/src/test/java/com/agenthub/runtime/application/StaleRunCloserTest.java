package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动只把「最后一轮还没写完」的遗留 RUNNING 收成 CANCELLED。
 * 已经写完的多轮、本进程正在跑的、以及行已被新请求推进的，不收。不连库。
 */
@ExtendWith(MockitoExtension.class)
class StaleRunCloserTest {

    @Mock
    private RunSessionRepository sessions;
    @Mock
    private RunEventRepository events;
    @Mock
    private TrialFlight flight;
    @Mock
    private TrialLiveBuffer live;
    @Mock
    private TrialObsHooks obs;

    private StaleRunCloser closer;

    @BeforeEach
    void setUp() {
        closer = new StaleRunCloser(sessions, events, flight, live, obs);
        // 缺省按「还有没写完的一轮」：旧用例测的就是这种遗留行
        lenient().when(events.listClosureMarks(any())).thenAnswer(invocation -> openMarks(invocation.getArgument(0)));
    }

    /**
     * 进程重启后库里仍是 RUNNING、最后一轮没写完、本进程没有单飞：写成 CANCELLED 并收 trace。
     */
    @Test
    void restartMarksLeftoverRunningCancelled() {
        RunSession leftover = running(8L);
        when(sessions.listRunning(null, null)).thenReturn(List.of(leftover));
        when(flight.running(8L)).thenReturn(false);
        when(live.running(8L)).thenReturn(false);
        when(sessions.interruptIfUnchanged(eq(8L), eq(leftover.getUpdateTime()), any())).thenReturn(true);

        int changed = closer.recoverAfterRestart();

        assertEquals(1, changed);
        assertEquals(RunStatus.CANCELLED.name(), leftover.getStatus());
        assertNotNull(leftover.getEndTime());
        verify(obs).cancel(8L);
        verify(sessions, never()).logicDelete(any());
    }

    /**
     * 已经写出终答的多轮仍是 RUNNING：重启不能改成取消。
     */
    @Test
    void restartLeavesFinishedSessionRunning() {
        RunSession finished = running(6L);
        when(sessions.listRunning(null, null)).thenReturn(List.of(finished));
        when(flight.running(6L)).thenReturn(false);
        when(live.running(6L)).thenReturn(false);
        when(events.listClosureMarks(any())).thenReturn(List.of(
                mark(6L, 1, EventType.USER),
                mark(6L, 2, EventType.ASSISTANT)));

        int changed = closer.recoverAfterRestart();

        assertEquals(0, changed);
        assertEquals(RunStatus.RUNNING.name(), finished.getStatus());
        verify(sessions, never()).interruptIfUnchanged(any(), any(), any());
        verify(obs, never()).cancel(anyLong());
    }

    /**
     * 启动扫描进行中又有请求占上单飞或内存快照：这一行保持 RUNNING。
     */
    @Test
    void restartSkipsSessionStillInThisProcess() {
        RunSession busy = running(3L);
        RunSession mirrored = running(4L);
        when(sessions.listRunning(null, null)).thenReturn(List.of(busy, mirrored));
        when(flight.running(3L)).thenReturn(true);
        when(live.running(4L)).thenReturn(true);

        int changed = closer.recoverAfterRestart();

        assertEquals(0, changed);
        assertEquals(RunStatus.RUNNING.name(), busy.getStatus());
        assertEquals(RunStatus.RUNNING.name(), mirrored.getStatus());
        verify(sessions, never()).interruptIfUnchanged(any(), any(), any());
        verify(obs, never()).cancel(anyLong());
    }

    /**
     * 条件更新没中（新请求已经推进了 update_time）：不当作已中断，也不动 trace。
     */
    @Test
    void restartIgnoresRowMovedByNewRequest() {
        RunSession session = running(5L);
        when(sessions.listRunning(null, null)).thenReturn(List.of(session));
        when(flight.running(5L)).thenReturn(false);
        when(live.running(5L)).thenReturn(false);
        when(sessions.interruptIfUnchanged(eq(5L), eq(session.getUpdateTime()), any())).thenReturn(false);

        int changed = closer.recoverAfterRestart();

        assertEquals(0, changed);
        assertEquals(RunStatus.RUNNING.name(), session.getStatus());
        verify(obs, never()).cancel(anyLong());
    }

    /**
     * 停机只收还占着单飞的会话，即使此刻 flight.running 仍为 true。
     */
    @Test
    void shutdownInterruptsInFlightSessions() {
        RunSession session = running(9L);
        when(flight.snapshot()).thenReturn(Set.of(9L));
        when(sessions.findById(9L)).thenReturn(Optional.of(session));
        when(sessions.interruptIfUnchanged(eq(9L), eq(session.getUpdateTime()), any())).thenReturn(true);

        int changed = closer.recoverOnShutdown();

        assertEquals(1, changed);
        assertEquals(RunStatus.CANCELLED.name(), session.getStatus());
        verify(obs).cancel(9L);
    }

    /**
     * 一轮没走到成功/失败收尾：单飞已释放时收成取消。仍被占用则不动。
     */
    @Test
    void abandonIfIdleClosesOnlyWhenNobodyHoldsTheSession() {
        RunSession idle = running(11L);
        when(flight.running(11L)).thenReturn(false);
        when(live.running(11L)).thenReturn(false);
        when(sessions.findById(11L)).thenReturn(Optional.of(idle));
        when(sessions.interruptIfUnchanged(eq(11L), eq(idle.getUpdateTime()), any())).thenReturn(true);

        closer.abandonIfIdle(11L);
        assertEquals(RunStatus.CANCELLED.name(), idle.getStatus());

        when(flight.running(12L)).thenReturn(true);
        closer.abandonIfIdle(12L);
        verify(sessions, never()).findById(12L);
    }

    /**
     * 一行收口抛错，后面的遗留行仍要处理。
     */
    @Test
    void oneFailureDoesNotStopTheRest() {
        RunSession broken = running(1L);
        RunSession next = running(2L);
        when(sessions.listRunning(null, null)).thenReturn(List.of(broken, next));
        when(flight.running(1L)).thenReturn(false);
        when(live.running(1L)).thenReturn(false);
        when(flight.running(2L)).thenReturn(false);
        when(live.running(2L)).thenReturn(false);
        when(sessions.interruptIfUnchanged(eq(1L), eq(broken.getUpdateTime()), any()))
                .thenThrow(new IllegalStateException("db"));
        when(sessions.interruptIfUnchanged(eq(2L), eq(next.getUpdateTime()), any())).thenReturn(true);

        int changed = closer.recoverAfterRestart();

        assertEquals(1, changed);
        assertEquals(RunStatus.CANCELLED.name(), next.getStatus());
        verify(obs).cancel(2L);
        verify(obs, never()).cancel(1L);
    }

    private static List<RunEvent> openMarks(Collection<?> ids) {
        List<RunEvent> marks = new ArrayList<>();
        if (ids == null) {
            return marks;
        }
        for (Object id : ids) {
            marks.add(mark((Long) id, 1, EventType.USER));
        }
        return marks;
    }

    private static RunEvent mark(long sessionId, int seq, EventType type) {
        RunEvent event = new RunEvent();
        event.setSessionId(sessionId);
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setContentText("问");
        return event;
    }

    private static RunSession running(long id) {
        RunSession session = new RunSession();
        session.setId(id);
        session.setStatus(RunStatus.RUNNING.name());
        session.setMessageCount(1);
        session.setSessionTitle("新对话");
        session.setLastMsgPreview("hello");
        session.setUpdateTime(LocalDateTime.of(2026, 11, 13, 0, 30, 0));
        return session;
    }
}
