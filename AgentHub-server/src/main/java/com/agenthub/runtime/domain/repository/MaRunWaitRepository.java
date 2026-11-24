package com.agenthub.runtime.domain.repository;

import com.agenthub.runtime.domain.model.MaPauseSnapshot;

import java.util.Optional;

/**
 * 方案「停在子专家询问处」的持久化仓储。所属域：runtime。
 *
 * <p>一条会话同一时刻至多一条 WAITING 行：新的停等先把它标成 DONE，再插入新行；
 * 续跑成功 / 用户停止后再标 DONE / CANCELLED。查询只认最新一行 WAITING。
 * 真源是 MySQL：进程重启、刷新、离开再进都按这一行恢复，不依赖任何内存登记表。
 */
public interface MaRunWaitRepository {

    /**
     * 找当前仍在等待用户回复的方案状态。
     *
     * @param sessionId 会话主键
     * @return 停等快照；没有时为空
     */
    Optional<MaPauseSnapshot> findWaiting(long sessionId);

    /**
     * 保存新停等：先把同会话旧的 WAITING 行收成 DONE，再插入这一行。
     *
     * @param state 最新停等快照（含本轮已完成 / 待执行 / 已写键）
     */
    void save(MaPauseSnapshot state);

    /**
     * 续跑成功：同会话所有 WAITING 行收成 DONE。
     *
     * @param sessionId 会话主键
     * @return true 表示确实更新了行
     */
    boolean markDone(long sessionId);

    /**
     * 用户停止：同会话所有 WAITING 行收成 CANCELLED。
     *
     * @param sessionId 会话主键
     * @return true 表示确实更新了行
     */
    boolean markCancelled(long sessionId);
}
