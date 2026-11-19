package com.agenthub.runtime.infra.agentscope.builtin;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * browserUse 的会话表。所属域：runtime。
 * <p>
 * 一次工具调用结束不关浏览器。键是「试跑会话 + 专家」：同一位专家在同一次试跑里的后续调用接着操作这一页，
 * 别的专家拿不到。空闲超过 idle，或专家明确 close，才销毁。页上的操作都丢进该会话自己的单线程，
 * 避免 Playwright 被并发调用。
 */
@Component
public class BrowserSessionStore {

    /** 会话已经空闲超时，旧页面不能再被填或读 */
    public static final String EXPIRED = "浏览器会话已超时关闭，请重新打开页面。";
    /** 这个键上还没有成功打开过的页 */
    public static final String NO_PAGE = "还没有打开的页面，请先打开网址。";
    /** 专家调用了 close */
    public static final String CLOSED = "浏览器会话已关闭。";
    /** 同时活着的无头浏览器太多 */
    public static final String AT_CAP = "浏览器会话已达上限，请先关闭不用的会话或稍后再试。";

    private final BrowserEngine engine;
    private final Duration opTimeout;
    private final Duration idle;
    private final LongSupplier clock;
    private final int maxSessions;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    /**
     * 生产构造：无头 Chrome，空闲不足 1 秒时回落到 5 分钟，避免把会话配成「开了就扔」。
     *
     * @param opTimeout 单次启动、导航和元素操作的上限
     * @param idle      多久没人碰就销毁
     * @param maxSessions 同时保留的会话上限，小于 1 时按 1
     */
    @Autowired
    public BrowserSessionStore(
            @Value("${agenthub.runtime.browser-timeout:30s}") Duration opTimeout,
            @Value("${agenthub.runtime.browser-session-idle:5m}") Duration idle,
            @Value("${agenthub.runtime.browser-session-max:8}") int maxSessions) {
        this(new PlaywrightBrowserEngine(), normalizeTimeout(opTimeout), normalizeIdle(idle),
                System::currentTimeMillis, Math.max(1, maxSessions), true);
    }

    /**
     * 测试直接传入时钟和引擎，不启动后台清扫（由调用方把时钟拨过 idle 再触发下一次操作）。
     *
     * @param engine    打开页面的实现
     * @param opTimeout 单次操作上限
     * @param idle      空闲上限，测试可以短于 1 秒
     * @param clock     当前时间，毫秒
     * @param maxSessions 会话上限
     * @param sweep     true 时按 idle 的一半周期清扫，且不超过 30 秒
     */
    BrowserSessionStore(BrowserEngine engine, Duration opTimeout, Duration idle, LongSupplier clock,
                        int maxSessions, boolean sweep) {
        this.engine = engine;
        this.opTimeout = opTimeout == null ? Duration.ofSeconds(30) : opTimeout;
        this.idle = idle == null ? Duration.ofMinutes(5) : idle;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.maxSessions = Math.max(1, maxSessions);
        if (!sweep) {
            this.sweeper = null;
            return;
        }
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "browser-use-sweep");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(5L, Math.min(30L, Math.max(1L, this.idle.toSeconds() / 2)));
        sweeper.scheduleWithFixedDelay(this::sweepQuietly, period, period, TimeUnit.SECONDS);
    }

    /**
     * 打开网址。已有未过期的会话就在原浏览器里导航，不另起进程。
     *
     * @param key 试跑会话 + 专家
     * @param url 已通过网址闸门
     * @return 打开后的页面快照
     */
    public BrowserPagePort.PageView open(String key, String url) {
        Slot slot = retain(key, true);
        return call(slot, BrowserFailures.Phase.NAVIGATE, () -> {
            if (slot.page == null) {
                slot.page = engine.open(url, opTimeout);
            } else {
                slot.page.navigate(url, opTimeout);
            }
            return slot.page.read();
        });
    }

    /**
     * @param key      会话键
     * @param selector CSS 选择器
     * @param value    写入内容
     * @return 填完后的页面快照
     */
    public BrowserPagePort.PageView fill(String key, String selector, String value) {
        Slot slot = retain(key, false);
        return call(slot, BrowserFailures.Phase.ACTION, () -> {
            // 页是否还在只在会话线程上看，避免和正在进行的 open 抢
            if (slot.page == null) {
                throw new BrowserUseException(NO_PAGE);
            }
            slot.page.fill(selector, value, opTimeout);
            return slot.page.read();
        });
    }

    /**
     * @param key      会话键
     * @param selector CSS 选择器
     * @return 点击后的页面快照
     */
    public BrowserPagePort.PageView click(String key, String selector) {
        Slot slot = retain(key, false);
        return call(slot, BrowserFailures.Phase.ACTION, () -> {
            if (slot.page == null) {
                throw new BrowserUseException(NO_PAGE);
            }
            slot.page.click(selector, opTimeout);
            return slot.page.read();
        });
    }

    /**
     * @param key 会话键
     * @return 当前页快照，不导航
     */
    public BrowserPagePort.PageView read(String key) {
        Slot slot = retain(key, false);
        return call(slot, BrowserFailures.Phase.ACTION, () -> {
            if (slot.page == null) {
                throw new BrowserUseException(NO_PAGE);
            }
            return slot.page.read();
        });
    }

    /**
     * @param key 会话键
     * @return 截图字节
     */
    public byte[] screenshot(String key) {
        Slot slot = retain(key, false);
        return call(slot, BrowserFailures.Phase.ACTION, () -> {
            if (slot.page == null) {
                throw new BrowserUseException(NO_PAGE);
            }
            return slot.page.screenshot();
        });
    }

    /**
     * 中断这一次运行里还开着的浏览器。键是 {@code trial-会话-agent-专家}，按会话前缀收口。
     * 不等待正在进行的导航结束：取消排队中的操作，并尽快关掉页面，避免无头浏览器在后台继续点。
     *
     * @param sessionId 运行会话主键
     * @return 实际收掉的会话数
     */
    public int closeTrial(long sessionId) {
        String prefix = "trial-" + sessionId + "-agent-";
        List<Slot> doomed = new ArrayList<>();
        synchronized (slots) {
            for (Slot slot : slots.values()) {
                if (slot.key.startsWith(prefix)) {
                    doomed.add(detach(slot));
                }
            }
        }
        doomed.forEach(this::forceStop);
        return doomed.size();
    }

    /**
     * 明确关闭。之后同一个键的填表和读取都不再看到旧页面。
     *
     * @param key 会话键
     */
    public void close(String key) {
        List<Slot> doomed = new ArrayList<>();
        Slot target = null;
        boolean expiredGone = false;
        synchronized (slots) {
            doomed.addAll(sweepLocked());
            Slot current = slots.get(key);
            if (current == null) {
                expiredGone = doomed.stream().anyMatch(slot -> key.equals(slot.key));
            } else if (expired(current)) {
                doomed.add(detach(current));
                expiredGone = true;
            } else {
                target = detach(current);
            }
        }
        doomed.forEach(this::destroy);
        if (target != null) {
            destroy(target);
            return;
        }
        throw new BrowserUseException(expiredGone ? EXPIRED : NO_PAGE);
    }

    /** 停掉清扫线程，并关掉还活着的浏览器。 */
    @PreDestroy
    public void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        List<Slot> left;
        synchronized (slots) {
            left = new ArrayList<>(slots.values());
            slots.clear();
        }
        for (Slot slot : left) {
            destroy(slot);
        }
    }

    /**
     * 取出还能用的会话。过期的先摘下并在锁外关进程。
     *
     * @param key    会话键
     * @param create true 时没有就新建（打开）；false 时没有或已过期直接失败
     * @return 可继续用的槽
     */
    private Slot retain(String key, boolean create) {
        List<Slot> doomed = new ArrayList<>();
        Slot kept = null;
        BrowserUseException failure = null;
        synchronized (slots) {
            doomed.addAll(sweepLocked());
            boolean sweptThis = doomed.stream().anyMatch(slot -> key.equals(slot.key));
            Slot current = slots.get(key);
            if (current != null && expired(current)) {
                doomed.add(detach(current));
                sweptThis = true;
                current = null;
            }
            if (current != null) {
                kept = current;
            } else if (!create) {
                // 刚被清扫摘掉的是超时，不是「从来没打开」
                failure = new BrowserUseException(sweptThis ? EXPIRED : NO_PAGE);
            } else if (slots.size() >= maxSessions) {
                failure = new BrowserUseException(AT_CAP);
            } else {
                kept = new Slot(key);
                slots.put(key, kept);
            }
        }
        doomed.forEach(this::destroy);
        if (failure != null) {
            throw failure;
        }
        return kept;
    }

    private void sweepQuietly() {
        try {
            List<Slot> dead;
            synchronized (slots) {
                dead = sweepLocked();
            }
            for (Slot slot : dead) {
                destroy(slot);
            }
        } catch (RuntimeException ignored) {
            // 清扫失败不能影响下一次工具调用
        }
    }

    /** 只在持有 slots 锁时调用。返回已经从 map 摘下、尚未关进程的槽。 */
    private List<Slot> sweepLocked() {
        List<Slot> dead = new ArrayList<>();
        for (Slot slot : slots.values()) {
            if (expired(slot)) {
                dead.add(detach(slot));
            }
        }
        return dead;
    }

    private Slot detach(Slot slot) {
        slots.remove(slot.key, slot);
        slot.closed = true;
        return slot;
    }

    private boolean expired(Slot slot) {
        return clock.getAsLong() - slot.touchedAt >= idle.toMillis();
    }

    private <T> T call(Slot slot, BrowserFailures.Phase phase, SessionCall<T> body) {
        Future<T> future = slot.thread.submit(() -> {
            if (slot.closed || slot.page == null && phase != BrowserFailures.Phase.NAVIGATE) {
                throw new BrowserUseException(slot.closed ? CLOSED : NO_PAGE);
            }
            T value = body.run();
            slot.touchedAt = clock.getAsLong();
            return value;
        });
        slot.inflight = future;
        try {
            return future.get(opTimeout.toMillis() + 1500L, TimeUnit.MILLISECONDS);
        } catch (CancellationException ex) {
            // 用户点了停止：这次操作作废，调用方据此结束，不再等操作超时
            throw new BrowserUseException("打开网页失败：操作被中断");
        } catch (TimeoutException ex) {
            destroy(slot);
            throw new BrowserUseException(BrowserFailures.explain(ex, phase));
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof BrowserUseException failure) {
                if (slot.page == null) {
                    destroy(slot);
                }
                throw failure;
            }
            throw new BrowserUseException(BrowserFailures.explain(cause, phase));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrowserUseException("打开网页失败：操作被中断");
        } finally {
            if (slot.inflight == future) {
                slot.inflight = null;
            }
        }
    }

    /**
     * 中断路径：先取消正在等的操作，让工具线程立刻返回，再关页面。
     * 关页面放到单独的守护线程，避免卡在导航里的关闭把「停止」请求拖住。
     *
     * @param slot 已经从会话表摘下的槽
     */
    private void forceStop(Slot slot) {
        slot.closed = true;
        Future<?> running = slot.inflight;
        if (running != null) {
            running.cancel(true);
        }
        slot.thread.shutdownNow();
        BrowserPagePort page = slot.page;
        if (page == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            try {
                page.close();
            } catch (RuntimeException ignored) {
                // 页面可能已经随进程退出；停止结果不取决于这次关闭是否抛错
            }
        }, "browser-use-stop");
        closer.setDaemon(true);
        closer.start();
    }

    private void destroy(Slot slot) {
        synchronized (slots) {
            slots.remove(slot.key, slot);
            slot.closed = true;
        }
        try {
            slot.thread.submit(() -> {
                if (slot.page != null) {
                    try {
                        slot.page.close();
                    } catch (RuntimeException ignored) {
                        // 关闭失败不改变「会话已结束」这个结果
                    }
                    slot.page = null;
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 线程已经停，或关闭本身超时：下面 shutdownNow 兜底
        }
        slot.thread.shutdownNow();
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private static Duration normalizeIdle(Duration idle) {
        if (idle == null || idle.compareTo(Duration.ofSeconds(1)) < 0) {
            return Duration.ofMinutes(5);
        }
        return idle;
    }

    @FunctionalInterface
    private interface SessionCall<T> {
        T run();
    }

    /** 一个专家的一页。线程只服务这一页。 */
    private final class Slot {
        private final String key;
        private final ExecutorService thread;
        /** 当前页。中断从另一条线程读取，必须可见 */
        private volatile BrowserPagePort page;
        /** 正在等待的那一次操作。停止时取消它，工具线程不必等到操作超时 */
        private volatile Future<?> inflight;
        private long touchedAt;
        private volatile boolean closed;

        private Slot(String key) {
            this.key = key;
            this.touchedAt = clock.getAsLong();
            this.thread = Executors.newSingleThreadExecutor(r -> {
                Thread worker = new Thread(r, "browser-use");
                worker.setDaemon(true);
                return worker;
            });
        }
    }
}
