package com.agenthub.prompt.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内单飞加载器（防缓存击穿）。所属域：prompt。
 * <p>
 * 语义：同一个 key 在任一时刻只有一个线程真正执行加载，其余线程等待它的结果。
 * 冷 key + 高并发时，这是「不让 N 个请求同时穿透到 MySQL」的唯一保障；
 * 只有短 TTL 而没有单飞，会在每次过期瞬间形成尖峰。
 * <p>
 * 线程安全：内部状态是并发 map；同一 key 的等待者共享同一 CompletableFuture。
 * 注意：加载函数的异常会原样抛给所有等待者（阻断类失败必须一致地失败，不能一半成功一半降级）。
 * 本类只保证**单进程**互斥；多实例的场景由 Redis 共享缓存 + 显式失效覆盖，
 * 现阶段不引入分布式锁（架构定稿允许「进程内 mutex」）。
 */
public final class SingleFlight {

    private static final Logger log = LoggerFactory.getLogger(SingleFlight.class);

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    /**
     * 执行一次带单飞的加载。
     *
     * @param key      互斥粒度（通常是缓存键）
     * @param supplier 真正打 DB 的加载逻辑
     * @param <T>      结果类型
     * @return 加载结果；同一 key 的并发调用拿到同一个结果
     * @throws RuntimeException 加载函数抛出的异常会原样向上抛
     */
    @SuppressWarnings("unchecked")
    public <T> T run(String key, java.util.function.Supplier<T> supplier) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            // 已有线程在加载：等它的结果，失败也按同样异常抛出，保持一致
            try {
                return (T) running.join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw ex;
            }
        }
        try {
            T value = supplier.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            // 先移除再让等待者被唤醒后的下一轮能重新进入（避免把异常结果永久钉在 map 里）
            inFlight.remove(key, mine);
        }
    }

    /**
     * 仅供测试与排障：当前在飞的 key 数。
     *
     * @return 在飞数量
     */
    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * @param key 缓存键
     * @return true 表示已有线程在加载
     */
    public boolean isLoading(String key) {
        return inFlight.containsKey(key);
    }

    /**
     * 记录一次降级（缓存层失败但 DB 成功），供观测。
     *
     * @param key  缓存键
     * @param what 失败环节
     */
    public static void warnDegraded(String key, String what) {
        log.warn("提示词缓存降级 key={} stage={}", key, what);
    }
}
