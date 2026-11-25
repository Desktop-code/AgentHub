package com.agenthub.eval.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 鉴权上下文捕获/恢复。所属域：eval。
 * <p>证明：worker 内能看到请求线程捕获的 RequestAttributes；结束后 ThreadLocal 已清。
 */
class EvalSaRequestContextTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void requirePresentRejectsNull() {
        BizException ex = assertThrows(BizException.class, () -> EvalSaRequestContext.requirePresent(null));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未登录"));
    }

    @Test
    void runWithRestoresAttributesOnWorkerAndClearsAfter() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletRequestAttributes attrs = new ServletRequestAttributes(request, response);
        RequestContextHolder.setRequestAttributes(attrs);

        RequestAttributes captured = EvalSaRequestContext.capture();
        assertNotNull(captured);

        RequestContextHolder.resetRequestAttributes();
        assertNull(RequestContextHolder.getRequestAttributes());

        AtomicReference<RequestAttributes> seenInWorker = new AtomicReference<>();
        String result = EvalSaRequestContext.runWith(captured, () -> {
            seenInWorker.set(RequestContextHolder.getRequestAttributes());
            return "ok";
        });

        assertEquals("ok", result);
        assertNotNull(seenInWorker.get());
        assertEquals(captured, seenInWorker.get());
        // finally 清理：当前测试线程上不应残留
        assertNull(RequestContextHolder.getRequestAttributes());
    }
}
