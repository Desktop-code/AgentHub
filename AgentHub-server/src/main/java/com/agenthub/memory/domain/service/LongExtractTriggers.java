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
        return explicit(policy, MemoryTexts.wantsRemember(text));
    }

    /**
     * 显式通道已由调用方完成 L0 正则判定时的版本。
     * <p>
     * 生产链路用注入的 {@code RememberIntentDetector}（可吃 yml 正则）判 L0，
     * 再调用本重载；这样开关判定只有一处，规则来源却可以配置。
     *
     * @param policy        策略
     * @param wantsRemember L0 是否命中
     * @return 是否允许进入显式抽取
     */
    public static boolean explicit(MemoryPolicy policy, boolean wantsRemember) {
        return explicitEnabled(policy) && wantsRemember;
    }

    /**
     * 只判显式通道的两个开关（enable_long + long_extract_on_explicit），不判正文。
     * 供 L1 异步分类前与池线程重读策略时复用。
     *
     * @param policy 策略
     * @return 显式通道是否打开
     */
    public static boolean explicitEnabled(MemoryPolicy policy) {
        return on(policy == null ? null : policy.getEnableLong())
                && on(policy == null ? null : policy.getLongExtractOnExplicit());
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
