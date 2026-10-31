package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.MemoryItem;

import java.util.List;
import java.util.Optional;

/**
 * SHORT 热路径。所属域：memory。
 * key 为 agenthub:memory:short:{agentId}:{sessionId}，与对话窗口分开。
 * 空 Optional 表示 key 不存在（miss）；空列表表示命中但没有条目。
 */
public interface ShortMemoryCache {

    /**
     * @param agentId   Agent
     * @param sessionId 会话
     * @return miss 时为空
     */
    Optional<List<MemoryItem>> read(long agentId, long sessionId);

    /**
     * 整表覆盖并设置 TTL。调用方保证列表已去掉过期条。
     *
     * @param agentId   Agent
     * @param sessionId 会话
     * @param items     当前热数据
     * @param ttlSec    剩余秒数，至少 1
     */
    void write(long agentId, long sessionId, List<MemoryItem> items, int ttlSec);

    /**
     * MySQL 失败时删掉本次写入，或强制下次从库回补。
     *
     * @param agentId   Agent
     * @param sessionId 会话
     */
    void delete(long agentId, long sessionId);
}
