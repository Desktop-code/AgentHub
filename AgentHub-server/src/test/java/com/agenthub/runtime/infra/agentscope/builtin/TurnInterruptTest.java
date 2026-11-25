package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.application.TurnInterrupt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动中断要能停掉正在等待的浏览器操作，而不是只做一个标记。
 */
class TurnInterruptTest {

    /**
     * 没在跑的会话不能被当成已中断。
     */
    @Test
    void cancelWithoutARunningTurnDoesNothing() {
        TurnInterrupt interrupt = new TurnInterrupt(store());
        assertFalse(interrupt.cancel(4));
        assertFalse(interrupt.isCancelled(4));
    }

    /**
     * 导航还卡着时点停止：工具调用马上返回，浏览器按关闭路径收掉。
     */
    @Test
    void cancelUnblocksTheOpenCall() throws Exception {
        CountDownLatch insideOpen = new CountDownLatch(1);
        BrowserEngine engine = (url, timeout) -> {
            insideOpen.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BrowserUseException("打开网页失败：操作被中断");
            }
            throw new BrowserUseException("导航不该在停止后继续跑完");
        };
        BrowserSessionStore sessions = new BrowserSessionStore(
                engine, Duration.ofSeconds(30), Duration.ofMinutes(5), () -> 1_000L, 4, false);
        TurnInterrupt interrupt = new TurnInterrupt(sessions);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            interrupt.bind(12);
            try {
                sessions.open("trial-12-agent-3", "https://example.com");
            } catch (RuntimeException ex) {
                failure.set(ex);
            }
        });
        worker.start();
        assertTrue(insideOpen.await(5, TimeUnit.SECONDS), "导航没有开始");
        assertTrue(interrupt.cancel(12));
        worker.join(3_000);
        assertFalse(worker.isAlive(), "停止后仍在等导航");
        assertTrue(failure.get() instanceof BrowserUseException, String.valueOf(failure.get()));
        assertTrue(failure.get().getMessage().contains("操作被中断"), failure.get().getMessage());
    }

    private static BrowserSessionStore store() {
        BrowserEngine engine = (url, timeout) -> new BrowserPagePort() {
            @Override
            public void navigate(String next, Duration opTimeout) {
            }

            @Override
            public PageView read() {
                return new PageView("", "", false, false);
            }

            @Override
            public void fill(String selector, String value, Duration opTimeout) {
            }

            @Override
            public void click(String selector, Duration opTimeout) {
            }

            @Override
            public byte[] screenshot() {
                return new byte[0];
            }

            @Override
            public void close() {
            }
        };
        return new BrowserSessionStore(engine, Duration.ofSeconds(2), Duration.ofMinutes(5), () -> 1_000L, 4, false);
    }
}
