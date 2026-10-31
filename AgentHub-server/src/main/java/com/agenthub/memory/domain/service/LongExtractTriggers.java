package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.MemoryPolicy;

/**
 * 长期偏好抽取该不该跑。所属域：memory。每一轮都不抽。
 */
public final class LongExtractTriggers {

    private LongExtractTriggers() {
    }

    /**
     * 显式记住可以在抽取总开关关闭时仍打开。写入仍要求 enable_long。
     *
     * @param policy 策略
     * @param text   用户原文
     * @return 是否当轮抽取
     */
    public static boolean explicit(MemoryPolicy policy, String text) {
        return on(policy == null ? null : policy.getEnableLong())
                && on(policy == null ? null : policy.getLongExtractOnExplicit())
                && MemoryTexts.wantsRemember(text);
    }

    /**
     * @param policy 策略
     * @return 会话结束是否抽取
     */
    public static boolean sessionEnd(MemoryPolicy policy) {
        return extractEnabled(policy) && on(policy.getLongExtractOnSessionEnd());
    }

    /**
     * 滚动摘要触顶时顺带抽即将 trim 的旧段。没有单独开关，跟抽取总开关走。
     *
     * @param policy 策略
     * @return 是否顺带抽
     */
    public static boolean onSummary(MemoryPolicy policy) {
        return extractEnabled(policy);
    }

    private static boolean extractEnabled(MemoryPolicy policy) {
        return policy != null && on(policy.getEnableLong()) && on(policy.getEnableLongExtract());
    }

    private static boolean on(Integer flag) {
        return flag != null && flag == 1;
    }
}
