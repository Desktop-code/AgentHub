package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PromptInjectionStats;

/**
 * 把本轮注入统计交给模型调用。所属域：runtime。
 * 在打开模型之前放入，LLM span 结束时读取。必须在请求结束时清掉。
 */
public final class PromptInjectionHolder {

    private static final ThreadLocal<PromptInjectionStats> CURRENT = new ThreadLocal<>();

    private PromptInjectionHolder() {
    }

    /**
     * @param stats 本轮装配结果，可空表示没有注入信息
     */
    public static void set(PromptInjectionStats stats) {
        if (stats == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(stats);
    }

    /**
     * @return 当前线程的注入统计；没有则为 null
     */
    public static PromptInjectionStats get() {
        return CURRENT.get();
    }

    /**
     * 试跑结束时调用，避免线程池把上一轮的截断标记带到下一轮。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
