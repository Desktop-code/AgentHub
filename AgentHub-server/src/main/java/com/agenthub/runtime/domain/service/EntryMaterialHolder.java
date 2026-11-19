package com.agenthub.runtime.domain.service;

/**
 * 「本轮入口预处理素材」的线程内标记。所属域：runtime。
 *
 * <p>为什么要有一个独立的载体：素材（识图 / 抽字 / 转写正文）是从入口预处理产出的，
 * 它要一路跟到被分到的子专家，但沿途要经过 Router 的六套模板与 {@code ChildRunner}。
 * 给 {@code RouterTrialExecutor.execute} 再加参数会牵动所有公开重载与既有桩；
 * 与 {@link LeafChoiceHolder} 同一做法——在调用入口放置、编排内部读取。
 *
 * <p>与 {@link LeafChoiceHolder} 的关键差别：<b>素材必须能被并行子读到</b>，
 * 而 ThreadLocal 不跨线程继承。因此派发侧（{@code ChildRunner}）必须在提交任务前
 * 取一次快照，再用 {@link #callAs} 把它带进 worker，纪律与 {@link ScratchContext} 一致。
 *
 * <p>线程相关注意：只在「本轮」有效，请求结束必须 {@link #clear()}，
 * 否则线程池复用会把上一轮的票面带到下一轮别人的会话里。
 */
public final class EntryMaterialHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private EntryMaterialHolder() {
    }

    /**
     * 放置本轮素材。
     *
     * @param material 附件派生正文；空或空白表示本轮没有素材（清掉标记，与未设置等价）
     */
    public static void set(String material) {
        if (material == null || material.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(material.trim());
    }

    /**
     * @return 当前线程看到的素材；没有则为空串（调用方按「无素材」处理，不判 null）
     */
    public static String current() {
        String value = CURRENT.get();
        return value == null ? "" : value;
    }

    /**
     * 带着指定素材执行一段逻辑，结束后恢复外层值。
     *
     * <p>用途：{@code ChildRunner} 把素材带进并行 worker / 嵌套泳道线程；
     * 恢复而不是无脑 remove，避免串行子嵌套在根作用域内时把外层素材抹掉。
     *
     * @param material 本轮素材；空白时直接执行，不绑定
     * @param body     业务体
     * @param <T>      返回类型
     * @return body 的结果
     */
    public static <T> T callAs(String material, java.util.function.Supplier<T> body) {
        if (material == null || material.isBlank()) {
            return body.get();
        }
        String previous = CURRENT.get();
        CURRENT.set(material.trim());
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 请求结束时清掉：线程池复用时不能把上一轮的素材带给下一轮。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
