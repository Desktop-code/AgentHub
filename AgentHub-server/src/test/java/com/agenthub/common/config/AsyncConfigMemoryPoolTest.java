package com.agenthub.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * memory 池的口径守卫。所属域：common 的 config。
 * 显式记住必须离开请求线程：一旦有人把拒绝策略改回 CallerRuns，池满时任务会退回 Tomcat 线程
 * 同步跑 60s 抽取，N6 红线会在高并发下复发。这里把「有界 + Abort + 命名」钉死在单测里。
 */
class AsyncConfigMemoryPoolTest {

    /**
     * 队列必须有界、拒绝必须不回调提交线程、线程名必须可辨认。
     */
    @Test
    void memoryPoolIsBoundedAndNeverRunsOnCallerThread() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().memoryTaskExecutor();
        try {
            assertEquals("memory-task-", executor.getThreadNamePrefix());
            // 队列容量固定 200：饱和后立即拒绝，而不是无界堆积吃内存
            assertEquals(200, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
            assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler()
                            instanceof ThreadPoolExecutor.AbortPolicy,
                    "memory 池不得使用 CallerRuns：退回提交线程等于把 60s 抽取塞回首字路径");
            // 核心线程不超上限，避免无界增长
            assertTrue(executor.getCorePoolSize() <= executor.getMaxPoolSize());
        } finally {
            executor.shutdown();
        }
    }
}
