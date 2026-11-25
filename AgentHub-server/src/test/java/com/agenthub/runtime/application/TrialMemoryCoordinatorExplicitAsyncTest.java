package com.agenthub.runtime.application;

import com.agenthub.memory.application.ContextWindowFacade;
import com.agenthub.memory.application.ExplicitMemoryExtractor;
import com.agenthub.memory.application.LongExtractService;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.application.MemoryRecallFacade;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.WindowState;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.RunSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户轮准备：显式记住必须只投递、不抽取。所属域：runtime。
 * 回归目标：prepareUser 里出现任何对 LongExtractService 的<b>抽取</b>调用（onSessionEnd / onExplicitConfirmed）
 * 都算破坏 N6 红线；允许且必须有的只有「清空上一轮空闲批抽标记」。
 */
@ExtendWith(MockitoExtension.class)
class TrialMemoryCoordinatorExplicitAsyncTest {

    @Mock
    private MemoryPolicyAppService policies;
    @Mock
    private ContextWindowFacade windows;
    @Mock
    private MemoryRecallFacade recall;
    @Mock
    private LongExtractService extracts;
    @Mock
    private ExplicitMemoryExtractor explicitMemory;
    @Mock
    private SessionTurnSource events;

    private TrialMemoryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new TrialMemoryCoordinator(policies, windows, recall, extracts, explicitMemory, events);
    }

    /** 说「请记住…」：只投递任务，主流程不碰抽取服务，窗口与渲染照常走完。 */
    @Test
    void prepareUserDispatchesInsteadOfExtracting() {
        RunSession session = session();
        when(policies.effective(1L)).thenReturn(new MemoryPolicy());
        when(windows.onUser(any(), anyLong(), anyLong(), anyLong(), anyString())).thenReturn(new WindowState());
        when(recall.shorts(any(), anyLong(), anyLong())).thenReturn(List.of());
        when(windows.renderUser(any(), anyString(), any())).thenReturn("rendered");

        String userText = "请记住我住广州";
        assertEquals("rendered", coordinator.prepareUser(session, userText));

        // 投递参数完整（Agent / 用户 / 会话 / 原文），异步任务才有正确的归属
        verify(explicitMemory).dispatch(any(), eq(1L), eq(2L), eq(3L), eq(userText));
        // 新一轮开口：上一轮空闲批抽终态标记必须被清掉，否则本会话永远不会再抽
        verify(extracts).clearSessionMarker(3L);
        // 红线断言：请求线程上没有同步抽取
        verify(extracts, never()).onSessionEnd(any(), anyLong(), anyLong(), anyLong(), anyString());
        verifyNoMoreInteractions(extracts);
    }

    /** 陈述句：仍会经过投递器，但投递器内部立即返回（此处断言主流程不抽取即可）。 */
    @Test
    void factStatementStillDoesNotExtractInline() {
        RunSession session = session();
        when(policies.effective(1L)).thenReturn(new MemoryPolicy());
        when(windows.onUser(any(), anyLong(), anyLong(), anyLong(), anyString())).thenReturn(new WindowState());
        when(recall.shorts(any(), anyLong(), anyLong())).thenReturn(List.of());
        when(windows.renderUser(any(), anyString(), any())).thenReturn("rendered");

        String userText = "我居住地是广州";
        coordinator.prepareUser(session, userText);

        verify(explicitMemory).dispatch(any(), eq(1L), eq(2L), eq(3L), eq(userText));
        verify(extracts).clearSessionMarker(3L);
        verify(extracts, never()).onSessionEnd(any(), anyLong(), anyLong(), anyLong(), anyString());
    }

    private static RunSession session() {
        RunSession session = new RunSession();
        session.setId(3L);
        session.setAgentId(1L);
        session.setUserId(2L);
        return session;
    }
}
