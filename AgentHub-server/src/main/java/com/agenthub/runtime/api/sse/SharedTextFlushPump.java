package com.agenthub.runtime.api.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 生产用定时 flush 泵：一条守护线程的共享调度器。所属域：runtime（出站层）。
 *
 * <p>为什么共享而不是每条流一个线程：合批时间窗是几十毫秒量级，任务体只做一次
 * 「把缓冲里的半句话写成两帧」，耗时在微秒级；一条线程足以服务所有并发流，
 * 也不会因为某条流没收尾就多挂一个线程。
 *
 * <p>为什么是守护线程：服务关闭 / 容器回收 JVM 时不能因为泵线程而卡住退出。
 *
 * <p>异常策略：任务体内自行兜住所有异常。一旦有异常冒泡到调度器，该次任务之后
 * 调度器仍可用（{@code ScheduledExecutorService} 只吞掉这一个任务），但为了不留隐患，
 * 这里仍统一 try/catch 并只打 debug 日志——出站失败不能影响业务线程的收尾。
 */
final class SharedTextFlushPump implements TextFlushPump {

    private static final Logger log = LoggerFactory.getLogger(SharedTextFlushPump.class);

    /** 进程级单例；类加载即创建，不需要 Spring 托管 */
    private static final SharedTextFlushPump INSTANCE = new SharedTextFlushPump();

    /** 单线程调度器：任务极短，串行执行反而省掉并发写响应的顾虑 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agenthub-sse-flush");
        thread.setDaemon(true);
        return thread;
    });

    private SharedTextFlushPump() {
    }

    /**
     * @return 共享实例
     */
    static SharedTextFlushPump instance() {
        return INSTANCE;
    }

    @Override
    public void schedule(long delayMillis, Runnable task) {
        if (task == null) {
            return;
        }
        try {
            // 至少 1ms：0 延迟在部分实现里等价于「立刻在泵线程执行」，没必要
            scheduler.schedule(() -> runSafely(task), Math.max(delayMillis, 1L), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            // 调度器已关闭（容器停机途中）：尾包由收尾路径的 flush 兜底，这里只记录
            log.debug("定时 flush 登记失败 type={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * 执行一次定时任务，不让异常冒泡到调度线程。
     *
     * @param task 任务体
     */
    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            // 连接可能已被容器回收：出站失败不影响业务线程，只留 debug 痕迹
            log.debug("定时 flush 执行失败 type={}", ex.getClass().getSimpleName());
        }
    }
}
