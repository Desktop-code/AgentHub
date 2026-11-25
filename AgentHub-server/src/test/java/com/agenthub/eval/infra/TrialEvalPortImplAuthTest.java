package com.agenthub.eval.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.application.EvalRunFlight;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.EvalTrialRequest;
import com.agenthub.obs.domain.repository.ObsTraceRepository;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.application.TrialRunAppService;
import com.agenthub.runtime.application.TrialSessionAppService;
import com.agenthub.runtime.domain.model.RunStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrialEvalPort 鉴权透传。所属域：eval。
 * <p>覆盖：入口有 RequestAttributes 时 worker 调 send 仍可见；入口无身份 → UNAUTHORIZED 且不建会话。
 * 一律 mock trial 门面；不出站、不起服务。
 */
class TrialEvalPortImplAuthTest {

    private TrialSessionAppService sessions;
    private TrialRunAppService runs;
    private ObsTraceRepository traces;
    private EvalRunFlight flight;
    private TrialEvalPortImpl port;

    @BeforeEach
    void setUp() {
        sessions = mock(TrialSessionAppService.class);
        runs = mock(TrialRunAppService.class);
        traces = mock(ObsTraceRepository.class);
        flight = new EvalRunFlight();
        port = new TrialEvalPortImpl(sessions, runs, traces, flight);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 入口有登录 Web 上下文：超时 worker 内 send 被调用，且 RequestContextHolder 非空。
     */
    @Test
    void runCasePropagatesRequestAttributesToSendWorker() {
        bindWebContext();
        SessionResp session = new SessionResp();
        session.setId(42L);
        when(sessions.create(7L)).thenReturn(session);
        when(sessions.rename(eq(42L), anyString())).thenReturn(session);

        AtomicReference<RequestAttributes> attrsAtSend = new AtomicReference<>();
        MessageResp ok = new MessageResp();
        ok.setStatus(RunStatus.SUCCESS.name());
        ok.setAssistantPreview("hello");
        when(runs.send(eq(42L), eq("ping"))).thenAnswer(inv -> {
            attrsAtSend.set(RequestContextHolder.getRequestAttributes());
            return ok;
        });

        EvalArtifact artifact = port.runCase(req(7L, "ping"));
        assertEquals("hello", artifact.getFinalAnswer());
        assertEquals("42", artifact.getSessionId());
        assertNotNull(attrsAtSend.get(), "worker 调 send 时必须已恢复 RequestAttributes");
        verify(runs).send(42L, "ping");
        // 请求线程结束后不应被 worker 泄漏污染（本测线程即请求线程）
        assertNotNull(RequestContextHolder.getRequestAttributes());
    }

    /**
     * 入口无 Web 上下文：直接 UNAUTHORIZED，不 create / 不 send。
     */
    @Test
    void runCaseWithoutWebContextFailsBeforeCreate() {
        RequestContextHolder.resetRequestAttributes();
        BizException ex = assertThrows(BizException.class, () -> port.runCase(req(7L, "ping")));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未登录"));
        verify(sessions, never()).create(anyLong());
        verify(runs, never()).send(anyLong(), anyString());
    }

    /**
     * cancelSession 走请求线程门面（有上下文时）；无第二套 HTTP。
     */
    @Test
    void cancelSessionDelegatesToTrialFacade() {
        bindWebContext();
        port.cancelSession("99");
        verify(sessions).cancel(99L);
    }

    private static void bindWebContext() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }

    private static EvalTrialRequest req(long agentId, String message) {
        EvalTrialRequest request = new EvalTrialRequest();
        request.setAgentId(agentId);
        request.setEvalRunId(1L);
        request.setCaseKey("c0");
        request.setMessage(message);
        request.setTimeoutMs(5_000L);
        return request;
    }
}
