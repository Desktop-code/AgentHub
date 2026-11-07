package com.agenthub.memory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 记忆召回出站的「带超时执行」小工具。所属域：memory 的 application。
 * <p>
 * 为什么单独一层：Embedding 与 Rerank 都要加等待上限（主对话不能被一次出站拖住），
 * 但两者的业务语义不同，不该塞进同一个类里。这里只负责「在有界池上跑 + 最多等 N 毫秒 + 失败返回 null」，
 * 不含任何模型/collection 知识。
 * <p>
 * 线程契约：任务跑在 {@code memoryRecallExecutor}（有界队列 + Abort）。调用线程（Tomcat）
 * 只阻塞到超时上限；池满时<b>不</b>退回调用线程执行，直接返回 null 让上层降级。
 * <p>
 * 超时不等于取消成功：{@code future.cancel(true)} 只置中断位，底层 HTTP 客户端自身还有读超时，
 * 因此这里不保证出站立刻停止，只保证<b>本轮注入不被继续等待</b>。
 */
@Component
public class MemoryRecallGateway {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallGateway.class);

    private final ThreadPoolTaskExecutor recallExecutor;

    /**
     * @param recallExecutor 召回专用有界池
     */
    public MemoryRecallGateway(@Qualifier("memoryRecallExecutor") ThreadPoolTaskExecutor recallExecutor) {
        this.recallExecutor = recallExecutor;
    }

    /**
     * 单次带超时执行。
     *
     * @param action    日志用动作名（embed / search / rerank）
     * @param timeoutMs 等待上限毫秒；≤0 表示不设上限（仅排障用）
     * @param call      真正出站的动作
     * @param <T>       结果类型
     * @return 结果；池满/超时/中断/异常均为 null
     */
    public <T> T call(String action, long timeoutMs, Supplier<T> call) {
        Future<T> future;
        try {
            future = recallExecutor.submit(call::get);
        } catch (TaskRejectedException e) {
            // 池与队列都满：立即降级，绝不退回请求线程同步出站（那会吃掉首字延迟）
            log.warn("[MEMORY] 召回出站被拒绝 reason=pool_saturated action={}", action);
            return null;
        }
        try {
            return timeoutMs > 0 ? future.get(timeoutMs, TimeUnit.MILLISECONDS) : future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[MEMORY] 召回出站超时，本轮降级 action={} timeoutMs={}", action, timeoutMs);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[MEMORY] 召回出站被中断，本轮降级 action={}", action);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[MEMORY] 召回出站失败，本轮降级 action={} type={} msg={}",
                    action, cause.getClass().getSimpleName(), cause.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 召回出站异常，本轮降级 action={} type={} msg={}",
                    action, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
