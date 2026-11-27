package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 试跑详情 / 工作区下载只认归属人。不因管理员身份放行。
 *
 * <p>两个口径各钉一遍：工作台按会话（{@code agent_run_session.user_id}），
 * 画布调试按试跑运行（{@code workflow_run.triggered_by} + TRIAL + 不挂会话）。
 */
@ExtendWith(MockitoExtension.class)
class TrialAccessTest {

    @Mock
    private RunSessionRepository sessions;

    @Mock
    private WorkflowRunRepository workflowRuns;

    @Mock
    private OperatorContext operatorContext;

    /** 组装被测对象（三个依赖都走 mock，不连库）。 */
    private TrialAccess access() {
        return new TrialAccess(sessions, workflowRuns, operatorContext);
    }

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

        BizException ex = assertThrows(BizException.class, () -> access().require(1L));
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

        assertEquals(9L, access().require(1L).getUserId());
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

        BizException ex = assertThrows(BizException.class, () -> access().requireWorkbench(1L));
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

        assertEquals(1L, access().requireWorkbench(1L).getId());
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

        BizException ex = assertThrows(BizException.class, () -> access().requireWorkbench(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    /**
     * 画布调试没有会话，下载要按试跑运行的发起人放行（沙箱目录也叫 trial-&lt;runId&gt;）。
     */
    @Test
    void workspaceAllowsOwnTrialRun() {
        when(sessions.findById(136L)).thenReturn(Optional.empty());
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(workflowRuns.findById(136L)).thenReturn(Optional.of(trialRun(136L, 9L)));

        // 不抛异常即放行；返回值对调用方无意义（只做归属闸门）
        access().requireWorkspace(136L);
    }

    /**
     * 别人的调试运行不能凭 runId 下载产物。
     */
    @Test
    void workspaceRejectsOtherUsersTrialRun() {
        when(sessions.findById(136L)).thenReturn(Optional.empty());
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(workflowRuns.findById(136L)).thenReturn(Optional.of(trialRun(136L, 2L)));

        BizException ex = assertThrows(BizException.class, () -> access().requireWorkspace(136L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    /**
     * 挂在工作台上的运行不按 run 放行：它必须走会话那一支，避免绕开会话渠道校验。
     */
    @Test
    void workspaceRejectsWorkbenchRunWithoutSession() {
        when(sessions.findById(139L)).thenReturn(Optional.empty());
        when(operatorContext.requireUserId()).thenReturn(9L);
        WorkflowRun run = trialRun(139L, 9L);
        run.setTriggerType(WorkflowTriggerType.WORKBENCH.name());
        run.setWorkbenchSessionId(726L);
        when(workflowRuns.findById(139L)).thenReturn(Optional.of(run));

        BizException ex = assertThrows(BizException.class, () -> access().requireWorkspace(139L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    /**
     * 会话与运行都查不到时按「不存在」收口，不暴露别的主键是否存在。
     */
    @Test
    void workspaceRejectsUnknownId() {
        when(sessions.findById(9999L)).thenReturn(Optional.empty());
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(workflowRuns.findById(9999L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> access().requireWorkspace(9999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 造一条画布调试的运行行。
     *
     * @param id     运行主键
     * @param userId 发起人
     * @return TRIAL 运行（不挂工作台会话）
     */
    private static WorkflowRun trialRun(Long id, Long userId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setTriggerType(WorkflowTriggerType.TRIAL.name());
        run.setTriggeredBy(userId);
        run.setWorkbenchSessionId(null);
        return run;
    }
}
