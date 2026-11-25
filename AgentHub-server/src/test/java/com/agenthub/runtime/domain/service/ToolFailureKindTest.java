package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具失败类型判定：哪些值得再试、哪些必须立刻停。
 *
 * <p>为什么单独测：这条判定决定了「会不会为了凑满 3 次熔断而反复重打」。
 * 判错方向会把业务异常当超时重复重试，把页面堆满红条并把首错淹掉；
 * 反过来把瞬时故障当业务错，则一次抖动就直接放弃。
 */
class ToolFailureKindTest {

    @Test
    void socketAndConnectTimeoutsAreRetryable() {
        assertEquals(ToolFailureKind.Kind.RETRYABLE,
                ToolFailureKind.of(new SocketTimeoutException("Read timed out"), null));
        assertEquals(ToolFailureKind.Kind.RETRYABLE,
                ToolFailureKind.of(new TimeoutException("timed out"), null));
        assertEquals(ToolFailureKind.Kind.RETRYABLE,
                ToolFailureKind.of(new ConnectException("Connection refused"), null));
        assertEquals(ToolFailureKind.Kind.RETRYABLE,
                ToolFailureKind.of(new UnknownHostException("milvus.internal"), null));
    }

    @Test
    void transientHttpStatusesAreRetryable() {
        for (int status : new int[]{502, 503, 504, 429}) {
            assertTrue(ToolFailureKind.retryable(new RuntimeException("boom"), status), "status=" + status);
        }
    }

    @Test
    void businessExceptionsAreNotRetryable() {
        assertEquals(ToolFailureKind.Kind.FATAL,
                ToolFailureKind.of(new IllegalArgumentException("参数非法：topK 必须为 1..100"), null));
        assertEquals(ToolFailureKind.Kind.FATAL,
                ToolFailureKind.of(new IllegalStateException("集合 agenthub_memory_long 不存在"), null));
        assertEquals(ToolFailureKind.Kind.FATAL,
                ToolFailureKind.of(new SecurityException("无权限访问该知识库"), null));
        // 明确 4xx 同样不可重试
        assertFalse(ToolFailureKind.retryable(new RuntimeException("bad request"), 400));
        assertFalse(ToolFailureKind.retryable(new RuntimeException("forbidden"), 403));
        assertFalse(ToolFailureKind.retryable(new RuntimeException("not found"), 404));
    }

    @Test
    void abortItselfIsFatal() {
        assertEquals(ToolFailureKind.Kind.FATAL,
                ToolFailureKind.of(new TrialFailure("撞限：consecutive_fail_abort（连续失败 3 次）",
                        LimitHitReasons.CONSECUTIVE_FAIL), null));
    }

    /**
     * 业务关键字优先于瞬时关键字：文本里同时出现 503 与「参数」时，必须判成业务错。
     */
    @Test
    void businessMarkerWinsOverTransientMarker() {
        assertEquals(ToolFailureKind.Kind.FATAL,
                ToolFailureKind.of(new RuntimeException("503 参数非法：collection 不能为空"), null));
    }

    @Test
    void unknownExceptionsAreTreatedAsNotRetryable() {
        assertEquals(ToolFailureKind.Kind.UNKNOWN, ToolFailureKind.of(new RuntimeException("something odd"), null));
        // UNKNOWN 不参与重试：宁可停一次，也不反复重打凑熔断
        assertFalse(ToolFailureKind.retryable(new RuntimeException("something odd"), null));
        assertEquals(ToolFailureKind.Kind.UNKNOWN, ToolFailureKind.of(null, null));
    }

    /**
     * cause 链上的信息也参与判定：外层包装成 IOException，内层是连接被拒。
     */
    @Test
    void causeChainIsConsidered() {
        IOException wrapped = new IOException("调用知识库失败",
                new ConnectException("Connection refused"));
        assertEquals(ToolFailureKind.Kind.RETRYABLE, ToolFailureKind.of(wrapped, null));
    }
}
