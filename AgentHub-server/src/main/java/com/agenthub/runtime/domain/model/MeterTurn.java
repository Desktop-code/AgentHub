package com.agenthub.runtime.domain.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次「轮次」的计量序号发号器。所属域：runtime。
 * <p>
 * 只在一轮由客户端幂等键标识时创建：{@code turnKey} = 该轮幂等键。
 * 同一轮里每记一条流水取一个自增序号，拼成 {@code v1:{sessionId}:{turnKey}:{seq}} 作为
 * {@code billing_token_ledger.dedupe_key}。这样**同一轮的重复执行**（例如首轮已跑过模型、
 * 之后才因落库异常被释放重试）会拿到同一批键，由账本唯一索引兜住，绝不会重复计费。
 * <p>
 * 序号用 {@link AtomicInteger}：Router 子专家可能并行记账，取号必须线程安全。
 * 并发下取号顺序不稳定，最坏结果是「少兜一次」而不是「错误地丢掉一笔正常账」——
 * 键里带了 turnKey，不同轮之间不可能撞号。
 */
public final class MeterTurn {

    private final String turnKey;
    private final AtomicInteger seq = new AtomicInteger();

    /**
     * @param turnKey 本轮幂等键；调用方已保证非空
     */
    public MeterTurn(String turnKey) {
        this.turnKey = turnKey;
    }

    /**
     * @param sessionId 会话
     * @return 本轮下一条流水的业务唯一键
     */
    public String nextDedupeKey(Long sessionId) {
        return "v1:" + sessionId + ":" + turnKey + ":" + seq.incrementAndGet();
    }

    public String turnKey() {
        return turnKey;
    }
}
