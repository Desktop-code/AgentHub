package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史会话为什么会被说成「已取消」。所属域：runtime。
 *
 * <p>不连库。钉住：已经写出终答的会话，即使库列是 CANCELLED，也不按取消展示、也不挡发送；
 * 用户取消和没写完就被打断的一轮才是「会话已取消」。启动收口只允许改后一种。
 */
class SessionCancelFaceTest {

    @Test
    void finishedTurnIsNotAnOpenTurn() {
        RunEvent user = event(1, EventType.USER, "帮我整理文件");
        RunEvent assistant = event(2, EventType.ASSISTANT, "清单已经列完。");
        assertFalse(TurnStillOpen.of(List.of(assistant, user)));
        assertFalse(TurnStillOpen.of(List.of()));
        assertFalse(TurnStillOpen.of(null));
    }

    @Test
    void userWithoutAssistantIsStillOpen() {
        assertTrue(TurnStillOpen.of(List.of(event(1, EventType.USER, "继续"))));
        RunEvent user = event(1, EventType.USER, "再来一轮");
        RunEvent assistant = event(2, EventType.ASSISTANT, "上一轮结论");
        RunEvent again = event(3, EventType.USER, "补充一句");
        assertTrue(TurnStillOpen.of(List.of(again, assistant, user)));
    }

    @Test
    void errorOrClosedPlanEndsTheTurn() {
        RunEvent user = event(1, EventType.USER, "执行计划");
        RunEvent error = event(2, EventType.ERROR, "运行失败");
        assertFalse(TurnStillOpen.of(List.of(user, error)));
        RunEvent pending = event(2, EventType.PLAN,
                PlanJson.planPayload(new PlanView("p1", PlanState.PENDING, List.of())));
        assertFalse(TurnStillOpen.of(List.of(user, pending)));
    }

    @Test
    void finishedCancelIsShownAsStillOpenAndCanSend() {
        RunSession session = cancelled("");
        assertFalse(SessionCancelFace.blocksAsCancelled(session, false));
        assertFalse(SessionCancelFace.interruptible(session, false));
        assertEquals(RunStatus.RUNNING.name(), SessionCancelFace.statusForClient(session, false));
        TrialSendGate.assertSendable(session, false);
    }

    @Test
    void userCancelAndOpenTurnStayCancelled() {
        RunSession userCancel = cancelled(SessionCancelFace.USER_CANCEL_SUMMARY);
        assertTrue(SessionCancelFace.userCancelled(userCancel));
        assertTrue(SessionCancelFace.blocksAsCancelled(userCancel, false));
        assertEquals(RunStatus.CANCELLED.name(), SessionCancelFace.statusForClient(userCancel, false));
        assertThrows(BizException.class, () -> TrialSendGate.assertSendable(userCancel, false));

        RunSession interrupted = cancelled("");
        assertTrue(SessionCancelFace.blocksAsCancelled(interrupted, true));
        assertEquals(RunStatus.CANCELLED.name(), SessionCancelFace.statusForClient(interrupted, true));
        assertThrows(BizException.class, () -> TrialSendGate.assertSendable(interrupted, true));
    }

    @Test
    void restartMayCancelOnlyAnOpenRunningTurn() {
        RunSession running = new RunSession();
        running.setStatus(RunStatus.RUNNING.name());
        assertTrue(SessionCancelFace.interruptible(running, true));
        assertFalse(SessionCancelFace.interruptible(running, false));
        assertEquals(RunStatus.SUCCESS.name(), SessionCancelFace.statusForClient(success(), false));
    }

    private static RunSession cancelled(String summary) {
        RunSession session = new RunSession();
        session.setId(7L);
        session.setStatus(RunStatus.CANCELLED.name());
        session.setErrorSummary(summary);
        return session;
    }

    private static RunSession success() {
        RunSession session = new RunSession();
        session.setStatus(RunStatus.SUCCESS.name());
        return session;
    }

    private static RunEvent event(int seq, EventType type, String text) {
        RunEvent event = new RunEvent();
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setContentText(text);
        return event;
    }
}
