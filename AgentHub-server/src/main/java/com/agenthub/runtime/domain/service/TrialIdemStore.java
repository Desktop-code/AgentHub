package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.TrialIdempotencyRecord;

import java.time.Duration;
import java.util.Optional;

/**
 * 发消息幂等记录的跨进程快路径。所属域：runtime。
 * <p>
 * 实现（Redis）必须吞掉所有异常并降级：读失败 / 写失败都不能让发消息变成 5xx，
 * 也不能被当成「没有记录」而放行双跑——调用方拿到空 {@link Optional} /
 * {@code null} 时必须转走数据库唯一键判定（见 {@code TrialMessageIdempotency}）。
 */
public interface TrialIdemStore {

    /**
     * @param sessionId 会话
     * @param idemKey   幂等键
     * @return 记录；不存在或 Redis 不可用时为空
     */
    Optional<TrialIdempotencyRecord> get(Long sessionId, String idemKey);

    /**
     * 原子占位（SET NX）。
     *
     * @param record 含 sessionId / idemKey / fingerprint / state=RUNNING
     * @param ttl    存活时间
     * @return true 占位成功；false 已被别人占；<b>null 表示 Redis 不可用</b>（调用方必须走 DB 判定）
     */
    Boolean putIfAbsent(TrialIdempotencyRecord record, Duration ttl);

    /**
     * 覆盖写（回写终态 / 接管刷新）。
     *
     * @param record 记录
     * @param ttl    存活时间
     */
    void put(TrialIdempotencyRecord record, Duration ttl);

    /**
     * @param sessionId 会话
     * @param idemKey   幂等键
     */
    void delete(Long sessionId, String idemKey);
}
