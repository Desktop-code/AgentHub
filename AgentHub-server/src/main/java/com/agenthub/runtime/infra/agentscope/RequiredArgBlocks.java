package com.agenthub.runtime.infra.agentscope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮内「同一工具被必填闸门拦下」的计数。所属域：runtime 的 infra。
 * 用于在模型反复换假值时升级措辞，把「继续试」引导成「向用户追问」，避免空转到轮次上限。
 * 线程安全：并行子 Agent 可能同时调用同一工具名。
 */
public final class RequiredArgBlocks {

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    /**
     * @param toolName 工具调用名
     * @return 本次拦截之后该工具在本轮被拦的累计次数（从 1 开始）
     */
    public int record(String toolName) {
        String key = toolName == null || toolName.isBlank() ? "unknown" : toolName.trim();
        return counts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }
}
