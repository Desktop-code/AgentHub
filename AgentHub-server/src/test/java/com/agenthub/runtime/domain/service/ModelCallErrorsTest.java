package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型异常文案只看类型，不泄露异常原文。
 */
class ModelCallErrorsTest {

    @Test
    void socketMapsToConnectionHint() {
        RuntimeException wrapped = new RuntimeException("OpenAIException", new SocketException("Connection reset"));
        assertEquals("模型服务连接失败，请检查网络后重试", ModelCallErrors.describe(wrapped));
    }

    @Test
    void timeoutMapsToRetryHint() {
        assertEquals("模型调用超时，请稍后重试",
                ModelCallErrors.describe(new RuntimeException(new SocketTimeoutException("read timed out"))));
        assertEquals("模型调用超时，请稍后重试",
                ModelCallErrors.describe(new RuntimeException(new TimeoutException("block"))));
    }

    @Test
    void trialFailureMessageWins() {
        assertEquals("撞限：timeout（试跑超时）",
                ModelCallErrors.describe(new TrialFailure("撞限：timeout（试跑超时）", LimitHitReasons.TIMEOUT)));
    }

    @Test
    void unknownFallsBackWithoutLeakingMessage() {
        String text = ModelCallErrors.describe(new RuntimeException("sk-secret https://api.example.com/key"));
        assertEquals("模型调用失败", text);
        assertTrue(!text.contains("sk-") && !text.contains("http"));
    }
}
