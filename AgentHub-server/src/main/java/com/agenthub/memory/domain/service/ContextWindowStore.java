package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.ContextTurn;

import java.util.List;
import java.util.Optional;

/**
 * 对话滑动窗口。所属域：memory。
 * 窗口 key 是 agenthub:ctx:{sessionId}，摘要槽是 agenthub:ctx:summary:{sessionId}。
 * 与 SHORT 记忆条不是同一把 key。
 */
public interface ContextWindowStore {

    /**
     * @param sessionId 会话
     * @return 窗口 key 不存在时为空，表示需要从事件回补
     */
    Optional<List<ContextTurn>> readTurns(long sessionId);

    /**
     * @param sessionId 会话
     * @return 摘要；没有槽时为空串
     */
    String readSummary(long sessionId);

    /**
     * 同时写窗口和摘要，并按 idle TTL 续期。任一侧失败由实现抛运行时异常。
     *
     * @param sessionId 会话
     * @param summary   会话摘要，可空
     * @param turns     近轮
     * @param ttlSec    idle TTL
     */
    void save(long sessionId, String summary, List<ContextTurn> turns, int ttlSec);

    /**
     * 清 Redis 后下次从 agent_run_event 回补。
     *
     * @param sessionId 会话
     */
    void delete(long sessionId);
}
