package com.agenthub.memory.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryQuery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆条目仓储。所属域：memory。实现只在 infra，Entity 不出 infra。
 */
public interface MemoryItemRepository {

    /**
     * @param query 分页和筛选
     * @return 一页条目，正文在列表里保留，供控制台编辑
     */
    PageResult<MemoryItem> page(MemoryQuery query);

    /**
     * @param id 主键
     * @return 未删除条目
     */
    Optional<MemoryItem> findById(Long id);

    /**
     * 同一 Agent、用户、范围和正文哈希的有效行，用来合并完全相同正文。
     *
     * @param agentId 可空，空表示全局
     * @param userId  可空
     * @param scope   SHORT / LONG / AGENT
     * @param hash    正文哈希
     * @return 最新一条未过期且启用的记录
     */
    Optional<MemoryItem> findActiveByHash(Long agentId, Long userId, String scope, String hash);

    /**
     * LONG 同键合并：agent + user + scope + fact_key（deleted=0，启用未过期）。
     *
     * @param agentId Agent
     * @param userId  用户
     * @param scope   一般为 LONG
     * @param factKey 已归一的稳定事实键
     * @return 命中则最新一条
     */
    Optional<MemoryItem> findActiveByFactKey(Long agentId, Long userId, String scope, String factKey);

    /**
     * 回补和注入用。只含未删除、启用、未过期的 SHORT，按 id 升序。
     *
     * @param agentId   Agent
     * @param sessionId 会话
     * @param now       当前时间
     * @return 未过期短期记忆
     */
    List<MemoryItem> listActiveShort(Long agentId, Long sessionId, LocalDateTime now);

    /**
     * 注入 LONG 或 AGENT。按 importance 降序、id 降序。
     *
     * @param agentId Agent，可空
     * @param userId  LONG 时的用户；AGENT 传 null 表示不按用户过滤
     * @param scope   LONG 或 AGENT
     * @param limit   条数上限
     * @param now     当前时间
     * @return 未过期条目
     */
    List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit, LocalDateTime now);

    /**
     * @param agentId Agent
     * @param userId  用户，可空
     * @param scope   范围
     * @return 未删除条数，淘汰时用
     */
    int countActive(Long agentId, Long userId, String scope);

    /**
     * 超限时丢掉 importance 最低、再按更早的 id。
     *
     * @param agentId Agent
     * @param userId  用户，可空
     * @param scope   范围
     * @param keep    保留条数
     * @param operatorId 操作人
     */
    void evictOverflow(Long agentId, Long userId, String scope, int keep, Long operatorId);

    /**
     * @param item       新条目
     * @param operatorId 操作人
     * @return 新主键
     */
    Long insert(MemoryItem item, Long operatorId);

    /**
     * @param item       已存在的条目
     * @param operatorId 操作人
     */
    void update(MemoryItem item, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);

    /**
     * 逻辑删除已过期行。定时任务调用，列表读时也会顺手清。
     *
     * @param now 当前时间
     * @return 删掉的行数
     */
    int purgeExpired(LocalDateTime now);
}
