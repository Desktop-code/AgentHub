package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.domain.model.RunSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户取消和没写完就被打断的一轮禁发。
 * 已经写完却被标成 CANCELLED 的历史行不挡；历史 SUCCESS 也不挡。
 */
class TrialSendGateTest {

    @Test
    void runningSuccessFailedAreSendable() {
        assertDoesNotThrow(() -> TrialSendGate.assertSendable(status("RUNNING"), false));
        assertDoesNotThrow(() -> TrialSendGate.assertSendable(status("SUCCESS"), false));
        assertDoesNotThrow(() -> TrialSendGate.assertSendable(status("FAILED"), false));
    }

    @Test
    void openTurnCancelIsBlocked() {
        BizException ex = assertThrows(BizException.class,
                () -> TrialSendGate.assertSendable(status("CANCELLED"), true));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void finishedCancelIsSendable() {
        RunSession finished = status("CANCELLED");
        finished.setErrorSummary("");
        assertDoesNotThrow(() -> TrialSendGate.assertSendable(finished, false));
    }

    @Test
    void userCancelIsBlockedEvenAfterTheTurnClosed() {
        RunSession session = status("CANCELLED");
        session.setErrorSummary(SessionCancelFace.USER_CANCEL_SUMMARY);
        BizException ex = assertThrows(BizException.class, () -> TrialSendGate.assertSendable(session, false));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    private static RunSession status(String status) {
        RunSession session = new RunSession();
        session.setStatus(status);
        return session;
    }
}
