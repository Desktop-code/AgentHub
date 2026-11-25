package com.agenthub.runtime.application;

import com.agenthub.memory.application.ExplicitMemoryExtractor;
import com.agenthub.memory.application.LongExtractService;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.RunSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除 RUNNING 与空闲/取消共用会话级批抽；清空只 invalidate；开关关则不调模型侧 onSessionEnd 正文抽取。
 */
@ExtendWith(MockitoExtension.class)
class TrialDeleteLongExtractTest {

    @Mock
    private MemoryPolicyAppService policies;
    @Mock
    private com.agenthub.memory.application.ContextWindowFacade windows;
    @Mock
    private com.agenthub.memory.application.MemoryRecallFacade recall;
    @Mock
    private LongExtractService extracts;
    @Mock
    private ExplicitMemoryExtractor explicitMemory;
    @Mock
    private SessionTurnSource events;

    private TrialMemoryCoordinator memory;

    @BeforeEach
    void setUp() {
        memory = new TrialMemoryCoordinator(policies, windows, recall, extracts, explicitMemory, events);
    }

    @Test
    void onClosedReplaysTurnsAndCallsSessionEndExtract() {
        RunSession session = running();
        MemoryPolicy policy = enabledPolicy();
        when(policies.effective(4L)).thenReturn(policy);
        when(events.replay(3L)).thenReturn(List.of(ContextTurn.of("user", "请记住我喜欢简洁")));
        memory.onClosed(session);
        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(extracts).onSessionEnd(eq(policy), eq(4L), eq(7L), eq(3L), transcript.capture());
        assertTrue(transcript.getValue().contains("请记住我喜欢简洁"));
    }

    @Test
    void invalidateDoesNotExtractButClearsMarker() {
        memory.invalidate(3L);
        verify(windows).invalidate(3L);
        verify(extracts).clearSessionMarker(3L);
        verify(extracts, never()).onSessionEnd(any(), anyLong(), anyLong(), any(), any());
        // 清空既不批抽也不触发显式抽取（显式投递只在 prepareUser 里发生）
        verify(explicitMemory, never()).dispatch(any(), any(), any(), any(), any());
    }

    @Test
    void sessionEndSwitchOffStillInvokesServiceGate() {
        // coordinator 仍交给 LongExtractService；开关在 onSessionEnd 内拦截（见 LongExtractServiceSessionEndTest）
        RunSession session = running();
        MemoryPolicy policy = enabledPolicy();
        policy.setLongExtractOnSessionEnd(0);
        when(policies.effective(4L)).thenReturn(policy);
        when(events.replay(3L)).thenReturn(List.of(ContextTurn.of("user", "你好")));
        memory.onClosed(session);
        verify(extracts).onSessionEnd(eq(policy), eq(4L), eq(7L), eq(3L), any());
    }

    private static RunSession running() {
        RunSession session = new RunSession();
        session.setId(3L);
        session.setAgentId(4L);
        session.setUserId(7L);
        session.setStatus("RUNNING");
        return session;
    }

    private static MemoryPolicy enabledPolicy() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setEnableLongExtract(1);
        policy.setLongExtractOnSessionEnd(1);
        return policy;
    }
}
