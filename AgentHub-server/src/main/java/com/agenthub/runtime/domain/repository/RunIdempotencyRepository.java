package com.agenthub.runtime.domain.repository;

import com.agenthub.runtime.domain.model.TrialIdempotencyRecord;

import java.util.Optional;

/**
 * 发消息幂等记录的持久化口。所属域：runtime。
 * <p>
 * 表 {@code agent_run_idempotency} 的 {@code (session_id, idem_key)} 唯一键是**跨实例最终兜底**：
 * Redis 只是快路径，Redis 丢了 / 被清空 / 挂了，也不能让同一次请求跑两轮模型。
 * <p>
 * 实现必须把唯一冲突翻译成 {@link #insert} 返回 false（不抛 DAO 异常到应用层）。
 */
public interface RunIdempotencyRepository {

    /**
     * @param sessionId 会话
     * @param idemKey   幂等键
     * @return 已存在的记录；没有时为空
     */
    Optional<TrialIdempotencyRecord> find(Long sessionId, String idemKey);

    /**
     * 占位。唯一键冲突视为「已经有人占过」，返回 false 而不是抛异常。
     *
     * @param record 含 sessionId / idemKey / fingerprint / state=RUNNING
     * @return true 表示本次插入成功、占位归本次请求
     */
    boolean insert(TrialIdempotencyRecord record);

    /**
     * 回写终态（SUCCESS / FAILED）与结果摘要。
     *
     * @param record 含 sessionId / idemKey / state / runStatus / 预览与错误摘要
     * @return 命中的行数；0 表示记录已被清理
     */
    int updateResult(TrialIdempotencyRecord record);

    /**
     * 接管陈旧的 RUNNING（进程崩溃 / 重启后遗留）。只更新仍是 RUNNING 的行。
     *
     * @param record 含 sessionId / idemKey / 新 fingerprint
     * @return true 表示接管成功，可以执行
     */
    boolean takeOver(TrialIdempotencyRecord record);

    /**
     * 删除占位。执行前失败时调用，允许客户端用同一键重试。
     *
     * @param sessionId 会话
     * @param idemKey   幂等键
     */
    void delete(Long sessionId, String idemKey);
}
