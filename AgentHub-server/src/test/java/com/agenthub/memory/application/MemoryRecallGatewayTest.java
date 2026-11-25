package com.agenthub.memory.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 召回出站网关：超时、异常、池满都收敛成 null（上层据此降级）。
 * 只用本地线程池，无任何网络出站。
 */
class MemoryRecallGatewayTest {

    private ThreadPoolTaskExecutor executor;
    private MemoryRecallGateway gateway;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("memory-recall-test-");
        // 与生产同口径：池满即拒绝，不回退调用线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setDaemon(true);
        executor.initialize();
        gateway = new MemoryRecallGateway(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void returnsValueOnSuccess() {
        assertEquals("ok", gateway.call("embed", 500L, () -> "ok"));
    }

    @Test
    void timeoutReturnsNull() {
        String result = gateway.call("embed", 50L, () -> {
            sleepQuietly(1000L);
            return "late";
        });

        assertNull(result, "超时必须返回 null 让上层降级，不能把慢出站的结果注入本轮");
    }

    @Test
    void exceptionReturnsNull() {
        String result = gateway.call("search", 500L, () -> {
            throw new IllegalStateException("milvus down");
        });

        assertNull(result);
    }

    @Test
    void rejectedSubmissionReturnsNullInsteadOfRunningInline() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runOnCaller = new AtomicInteger();

        // 占满唯一线程 + 唯一队列位：第三次提交必然被拒绝
        executor.execute(() -> {
            started.countDown();
            awaitQuietly(release, 2000L);
        });
        awaitQuietly(started, 2000L);
        executor.execute(() -> awaitQuietly(release, 2000L));

        String result = gateway.call("embed", 200L, () -> {
            runOnCaller.incrementAndGet();
            return "inline";
        });

        assertNull(result);
        assertEquals(0, runOnCaller.get(), "池满时不得退回调用线程执行（那会让超时上限失效）");
        release.countDown();
    }

    @Test
    void zeroTimeoutWaitsForCompletion() {
        assertEquals("ok", gateway.call("embed", 0L, () -> "ok"));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
