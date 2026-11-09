package com.agenthub.runtime.domain.service;

/**
 * 发消息幂等缓存的键。所属域：runtime。
 * <p>
 * 键形态：{@code agenthub:idem:trial-msg:{sessionId}:{idemKey}}。
 * 会话进键是**硬要求**：同一个客户端键在不同会话里语义不同，混用会把 A 会话的结果重放给 B。
 * <p>
 * 与 C3 装配缓存、C1 权限缓存同为 {@code agenthub:{域}:{资源}:{标识}} 风格；集中在此，禁止调用点拼串。
 */
public final class TrialIdemCacheKeys {

    private static final String ROOT = "agenthub:idem";

    private TrialIdemCacheKeys() {
    }

    /**
     * @param sessionId 会话主键；调用方已保证非空
     * @param idemKey   已通过格式校验的幂等键（只含字母数字下划线短横）
     * @return 该会话下该键的缓存键
     */
    public static String trialMessage(Long sessionId, String idemKey) {
        return ROOT + ":trial-msg:" + sessionId + ":" + idemKey;
    }
}
