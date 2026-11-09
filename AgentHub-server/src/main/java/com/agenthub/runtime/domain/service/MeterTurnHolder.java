package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.MeterTurn;

/**
 * 当前线程所属轮次的计量上下文。所属域：runtime。
 * <p>
 * 为什么用 ThreadLocal：{@code MeterScope} 的构造点散落在 ReAct / Router / 计划执行 / OCR 多处，
 * 逐个改签名会把「发消息幂等」这刀扩散到编排内部。这里在轮次入口 set 一次，
 * {@code MeterScope.console(...)} 在构造时读取并**抓取引用**——
 * 记账发生在 {@code Flux#doFinally}（可能已换线程），靠抓取引用而不是再次读 ThreadLocal 才拿得到。
 * <p>
 * 边界：只在请求线程上生效。并行子任务自己新建的 MeterScope 可能读不到（dedupeKey 为空），
 * 后果是那一笔退回「无去重」的普通记账——宁可少兜一次，也不能错杀一笔正常账。
 * 必须成对调用 {@link #begin(String)} / {@link #clear()}，否则线程池会串味到下一轮。
 */
public final class MeterTurnHolder {

    private static final ThreadLocal<MeterTurn> CURRENT = new ThreadLocal<>();

    private MeterTurnHolder() {
    }

    /**
     * @param turnKey 本轮幂等键；空表示本轮不参与账本去重（清掉残留）
     */
    public static void begin(String turnKey) {
        if (turnKey == null || turnKey.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new MeterTurn(turnKey));
    }

    /**
     * @return 当前轮次的发号器；不在轮内或本轮无幂等键时为空
     */
    public static MeterTurn current() {
        return CURRENT.get();
    }

    /**
     * 轮次结束必须清掉：线程池复用会让上一轮的 turnKey 污染下一轮。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
