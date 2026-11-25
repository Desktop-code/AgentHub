package com.agenthub.obs.domain.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step_no 发号：串行整数、并行 N.1/N.2、点号支下再挂 .k；同父并行不抢号。
 */
class StepNoAllocatorTest {

    @Test
    void serialThenParallelForkThenNested() {
        StepNoAllocator alloc = new StepNoAllocator();
        assertEquals("1", alloc.next(null, null, false));
        assertEquals("2", alloc.next(10L, "1", false));
        assertEquals("3.1", alloc.next(10L, "1", true));
        assertEquals("3.2", alloc.next(10L, "1", true));
        assertEquals("3.1.1", alloc.next(21L, "3.1", false));
        assertEquals("4", alloc.next(10L, "1", false));
    }

    /**
     * 合问：子 AGENT parent=判路由 LLM（step 2），并行仍占新基号 3.x，禁止变成 2.1/2.2。
     */
    @Test
    void parallelUnderJudgeLlmKeepsIntegerBase() {
        StepNoAllocator alloc = new StepNoAllocator();
        Long routeId = 470L;
        Long judgeLlmId = 471L;
        assertEquals("1", alloc.next(null, null, false));
        assertEquals("2", alloc.next(routeId, "1", false));
        assertEquals("3.1", alloc.next(judgeLlmId, "2", true));
        assertEquals("3.2", alloc.next(judgeLlmId, "2", true));
        // 子内链挂本 AGENT
        assertEquals("3.1.1", alloc.next(480L, "3.1", false));
    }

    @Test
    void parallelFanoutIsThreadSafe() throws Exception {
        StepNoAllocator alloc = new StepNoAllocator();
        alloc.next(null, null, false); // 1
        alloc.next(1L, "1", false); // 2
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                got.add(alloc.next(1L, "1", true));
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(n, got.size());
        Set<String> unique = new HashSet<>(got);
        assertEquals(n, unique.size());
        for (String step : got) {
            assertTrue(step.startsWith("3."), step);
        }
    }
}
