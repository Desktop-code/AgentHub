package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败原因摘要：带 cause、带本仓帧、抹掉密钥与长 base64。
 */
class CauseDigestTest {

    @Test
    void keepsCauseChainAndOwnFrames() {
        RuntimeException root = failing();
        RuntimeException wrapped = new IllegalStateException("上游 401", root);
        String digest = CauseDigest.of(wrapped);
        assertTrue(digest.contains("IllegalStateException"), digest);
        assertTrue(digest.contains("上游 401"), digest);
        assertTrue(digest.contains("IOException"), digest);
        assertTrue(digest.contains("<-"), "应保留 cause 层级：" + digest);
        assertTrue(digest.contains("com.agenthub."), "应带本仓帧：" + digest);
    }

    @Test
    void masksKeysAndLongBase64() {
        String raw = "Authorization: Bearer sk-abcdef123456 api_key=secret123 "
                + "data:image/png;base64," + "A".repeat(200);
        String digest = CauseDigest.of(new IllegalStateException(raw));
        assertFalse(digest.contains("sk-abcdef123456"), digest);
        assertFalse(digest.contains("secret123"), digest);
        assertFalse(digest.contains("A".repeat(60)), digest);
    }

    @Test
    void nullAndSelfCauseAreSafe() {
        assertNull(CauseDigest.of(null));
        // JDK 不允许 initCause 自引用，用覆写 getCause 的匿名异常模拟「框架把自己挂成 cause」
        RuntimeException loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertTrue(CauseDigest.of(loop).contains("loop"));
    }

    @Test
    void trialFailureCarriesCauseWithoutLeakingIntoMessage() {
        IllegalStateException cause = new IllegalStateException("vision 400");
        TrialFailure failure = new TrialFailure("图片文字识别失败，请确认图片清晰后重试",
                LimitHitReasons.ERROR, cause);
        assertTrue(failure.getCause() == cause);
        assertFalse(failure.getMessage().contains("400"));
    }

    /** 制造一个带本仓栈帧的异常链（帧来自本测试类所在包）。 */
    private static RuntimeException failing() {
        try {
            throw new IOException("connect timed out");
        } catch (IOException ex) {
            return new RuntimeException("包装", ex);
        }
    }
}
