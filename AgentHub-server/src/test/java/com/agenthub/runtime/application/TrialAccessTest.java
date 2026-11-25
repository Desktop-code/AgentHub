package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 试跑详情只认会话所属用户。不因管理员身份放行。
 */
@ExtendWith(MockitoExtension.class)
class TrialAccessTest {

    @Mock
    private RunSessionRepository sessions;

    @Mock
    private OperatorContext operatorContext;

    /**
     * 别人的 sessionId 不能读。
     */
    @Test
    void rejectsOtherUsersSession() {
        RunSession session = new RunSession();
        session.setId(1L);
        session.setUserId(2L);
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(operatorContext.requireUserId()).thenReturn(9L);

        TrialAccess access = new TrialAccess(sessions, operatorContext);
        BizException ex = assertThrows(BizException.class, () -> access.require(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    /**
     * 本人可以继续看自己的会话。
     */
    @Test
    void allowsOwner() {
        RunSession session = new RunSession();
        session.setId(1L);
        session.setUserId(9L);
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(operatorContext.requireUserId()).thenReturn(9L);

        TrialAccess access = new TrialAccess(sessions, operatorContext);
        assertEquals(9L, access.require(1L).getUserId());
    }

    /**
     * 调试会话不能从工作台接口继续聊：两个入口权限与列表必须隔离。
     */
    @Test
    void workbenchRejectsDebugSession() {
        RunSession session = new RunSession();
        session.setId(1L);
        session.setUserId(9L);
        session.setEntryChannel("DEBUG");
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(operatorContext.requireUserId()).thenReturn(9L);

        TrialAccess access = new TrialAccess(sessions, operatorContext);
        BizException ex = assertThrows(BizException.class, () -> access.requireWorkbench(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    /**
     * 工作台会话按渠道放行，归属仍只认当前用户。
     */
    @Test
    void workbenchAllowsWorkbenchSession() {
        RunSession session = new RunSession();
        session.setId(1L);
        session.setUserId(9L);
        session.setEntryChannel("WORKBENCH");
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(operatorContext.requireUserId()).thenReturn(9L);

        TrialAccess access = new TrialAccess(sessions, operatorContext);
        assertEquals(1L, access.requireWorkbench(1L).getId());
    }

    /**
     * 存量行渠道为空时按 DEBUG 处理，避免历史调试会话漏进工作台。
     */
    @Test
    void workbenchTreatsBlankChannelAsDebug() {
        RunSession session = new RunSession();
        session.setId(1L);
        session.setUserId(9L);
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(operatorContext.requireUserId()).thenReturn(9L);

        TrialAccess access = new TrialAccess(sessions, operatorContext);
        BizException ex = assertThrows(BizException.class, () -> access.requireWorkbench(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }
}
