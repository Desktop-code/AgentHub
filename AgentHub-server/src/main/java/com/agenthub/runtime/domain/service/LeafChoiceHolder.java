package com.agenthub.runtime.domain.service;

/**
 * 「本轮叶子能否输出可点选项块」的线程内标记。所属域：runtime。
 *
 * <p>为什么用线程上下文而不是给 {@code RouterTrialExecutor.execute} 再加个参数：
 * 那是编排入口的公开签名，计划批准、试跑、工作台都从它进来；为一个只影响叶子提示词的开关
 * 改公开签名，会让所有既有调用点与桩一起变动。这里与 {@link PromptInjectionHolder} 同一做法：
 * 在调用入口放置、编排内部读取，请求结束必须清掉，避免线程池把上一轮的标记带到下一轮。
 *
 * <p>线程相关注意：只在「单意图内联执行」这一条路径上为 true（多意图并行每个子都抛一组选项
 * 会让用户无从拍板），因此标记只需要在当前请求线程可见；子任务线程读不到也只会退化成
 * 「不出选项块」，不会误开。
 */
public final class LeafChoiceHolder {

    private static final ThreadLocal<Boolean> CURRENT = new ThreadLocal<>();

    private LeafChoiceHolder() {
    }

    /**
     * 标记当前线程这一轮是否允许叶子输出选项块。
     *
     * @param enabled true 表示允许（仅工作台正式对话的单专家 / 入口直分叶子）
     */
    public static void set(boolean enabled) {
        if (!enabled) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(Boolean.TRUE);
    }

    /**
     * @return true 表示本轮允许叶子输出选项块
     */
    public static boolean enabled() {
        return Boolean.TRUE.equals(CURRENT.get());
    }

    /**
     * 请求结束时清掉：线程池复用时不能把上一轮的开关带给下一轮。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
