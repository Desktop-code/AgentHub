package com.agenthub.memory.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;

import java.time.LocalDateTime;
import java.util.Collection;
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
     * LONG 同族合并：按「canonical + 其所有 ascii 别名」批量查活跃行。
     * <p>
     * 为什么需要整族查：旧版本没有偏好族别名时，同一条偏好可能已经以
     * {@code favorite_food} / {@code food_preference} 分别落过两行。
     * 只按 canonical 查会再插一条，永远收敛不成一条；命中多行时由调用方留最新、
     * 其余走既有逻辑删 + 向量联删。
     *
     * @param agentId Agent
     * @param userId  用户
     * @param scope   一般为 LONG
     * @param factKeys 同族键集合（含 canonical 本身），空则返回空列表
     * @return 命中行，按 id 倒序（首条最新）；最多取 50 条防止异常数据拖满内存
     */
    List<MemoryItem> findActiveByFactKeys(Long agentId, Long userId, String scope,
                                          Collection<String> factKeys);

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
     * 注入 LONG 或 AGENT。按 importance 降序、更新时间降序、id 降序。
     * 只返回未删除、启用且未过期（expired_time 为空或晚于 now）的行。
     *
     * @param agentId Agent，可空
     * @param userId  LONG 时的用户；AGENT 传 null 表示不按用户过滤
     * @param scope   LONG 或 AGENT
     * @param limit   条数上限（LONG 传 Top N）
     * @param now     当前时间
     * @return 未过期条目
     */
    List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit, LocalDateTime now);

    /**
     * 带 importance 下限的注入读取：先按门槛过滤，再按 importance + 更新时间取 Top N。
     * <p>
     * 与 {@link #listActive(Long, Long, String, int, LocalDateTime)} 的唯一差别是多了
     * {@code minImportance}。实现方应在 SQL 里过滤，避免「先截 N 再过滤」导致条数不足；
     * 默认实现兜底在内存过滤，仅供测试替身使用。
     *
     * @param agentId       Agent，可空
     * @param userId        LONG 时的用户；AGENT 传 null 表示不按用户过滤
     * @param scope         LONG 或 AGENT
     * @param limit         条数上限（LONG 传 Top N）
     * @param minImportance importance 下限，0 表示不过滤
     * @param now           当前时间
     * @return 未过期且 importance 达标的条目
     */
    default List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit,
                                        int minImportance, LocalDateTime now) {
        List<MemoryItem> rows = listActive(agentId, userId, scope, limit, now);
        if (minImportance <= 0) {
            return rows;
        }
        return rows.stream()
                .filter(item -> (item.getImportance() == null ? 0 : item.getImportance()) >= minImportance)
                .toList();
    }

    /**
     * 按主键批量取「启用 + 未过期」的行，供向量候选回 MySQL 对齐。
     * <p>
     * 必须带 scope：向量库里同一主键只会是 LONG，但显式限定可避免未来混入其它范围。
     * 已删（deleted=1）/ 已过期 / 停用 的行不会返回，因此上层无需再判一次。
     *
     * @param memoryIds 主键；空集合直接返回空列表
     * @param scope     范围，一般为 LONG
     * @param now       当前时间
     * @return 未删除、启用、未过期的行；顺序不保证（上层按向量分数重排）
     */
    List<MemoryItem> listActiveByIds(Collection<Long> memoryIds, String scope, LocalDateTime now);

    /**
     * @param agentId Agent
     * @param userId  用户，可空
     * @param scope   范围
     * @return 未删除条数，淘汰时用
     */
    int countActive(Long agentId, Long userId, String scope);

    /**
     * 超限时淘汰：<b>先丢已过期</b>，再按 importance 低、更新更早者淘汰。
     * 已过期行也计入上限（否则它们会永远占着名额把有效条挤掉）。
     *
     * @param agentId Agent
     * @param userId  用户，可空
     * @param scope   范围
     * @param keep    保留条数
     * @param operatorId 操作人
     * @return 被淘汰（逻辑删除）的 memory_item.id；调用方据此联动删除向量
     */
    List<Long> evictOverflow(Long agentId, Long userId, String scope, int keep, Long operatorId);

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
     * 逻辑删除已过期行。定时任务调用。<b>返回的是 LONG 行的主键</b>：
     * 只有 LONG 在向量库里有对应点，SHORT / AGENT 即使被删也不需要联删（避免无意义的出站）。
     * <p>
     * 单轮最多处理 {@code limit} 行：过期行本来就被读路径过滤掉，短暂积压只影响表大小，
     * 但一次删几十万行会长时间持锁、并把这些主键整批塞给向量删除。
     *
     * @param now   当前时间
     * @param limit 本轮最多删除的行数，&gt;0
     * @return 被逻辑删除的 LONG 行主键；调用方据此联动删除向量
     */
    List<Long> purgeExpired(LocalDateTime now, int limit);

    /**
     * 批量落向量同步状态位。<b>只对 {@code scope='LONG'} 生效</b>：SQL 里硬锁 scope，
     * 传进来 SHORT / AGENT 的 id 会被静默忽略（它们本就不该有记忆向量）。
     * <p>
     * <b>不走 TableLogic</b>：逻辑删除/过期清理后的行同样要落终态 SYNCED，
     * 走 MyBatis-Plus 的 updateById 会被 {@code deleted=0} 条件挡掉、静默失败。
     * <p>
     * 只改状态位三列，<b>不改业务列、不写任何时间列</b>（禁止同步时间字段）。
     *
     * @param memoryIds 主键；空集合直接返回 0
     * @param status    目标状态
     * @param failCount 失败次数；成功/终态传 0（清零）
     * @param error     失败原因（脱敏截断）；成功/终态传 null
     * @return 实际更新行数（只统计 LONG 行）
     */
    int markVectorSync(Collection<Long> memoryIds, MemoryVectorSyncStatus status, int failCount, String error);

    /**
     * 只改状态位（不动失败次数与原因）。用于「本轮没做成但不该算失败」的场景。
     * <p>
     * 典型：向量联删时基础设施未就绪（Milvus / 实例 9 不可用）。此时把行拉回 PENDING，
     * 补同步就绪后会按「删点」分支收口；<b>不能</b>记一次失败，否则一次断网就把三振用完。
     *
     * @param memoryIds 主键；空集合直接返回 0
     * @param status    目标状态
     * @return 实际更新行数（只统计 LONG 行）
     */
    int markVectorSyncStatus(Collection<Long> memoryIds, MemoryVectorSyncStatus status);

    /**
     * 状态位置 {@code status} 并让失败次数 +1，同时写入短原因。用于联删失败（刀8）。
     * <p>
     * 为什么是自增而不是绝对值：联删入口（过期清理 / 淘汰 / 手删）拿不到行上的历史次数，
     * 传绝对值会把已有计数覆盖成 1，三振永远到不了上限。
     *
     * @param memoryIds 主键；空集合直接返回 0
     * @param status    目标状态（一般为 FAILED）
     * @param error     失败原因（脱敏截断）
     * @return 实际更新行数（只统计 LONG 行）
     */
    int bumpVectorSyncFail(Collection<Long> memoryIds, MemoryVectorSyncStatus status, String error);

    /**
     * 取「应该删向量点」的 LONG 候选主键：状态 PENDING/FAILED、失败次数未到上限，
     * 且满足任一终态条件（逻辑删除 / 停用 / 已过期）。
     * <p>
     * 为什么单独一条 SQL：{@link #listVectorSyncCandidates} 走的是「有效未过期」口径，
     * 已删/已过期行永远进不去，导致联删失败的行没有任何人接手、向量点永久滞留。
     * 这里显式包含 {@code deleted = 1}，必须绕过 TableLogic，故走 XML。
     *
     * @param now          当前时间（过期判定）
     * @param limit        本批上限，&gt;0
     * @param maxFailCount 允许重试的最大失败次数
     * @return 待删点的 memory_item.id，按 id 升序
     */
    List<Long> listVectorDeleteCandidates(LocalDateTime now, int limit, int maxFailCount);

    /**
     * 取该 Agent 下待补同步的 LONG 行：未删除、启用、未过期、状态为 PENDING/FAILED，
     * 且失败次数小于 {@code maxFailCount}（到上限后停在 FAILED、只打日志，不再捞）。
     * <p>
     * 按 id 升序，便于多轮之间稳定推进（不会反复卡在同一批新行上）。
     *
     * @param agentId      Agent
     * @param limit        本批上限
     * @param maxFailCount 允许重试的最大失败次数
     * @param now          当前时间（过期判定）
     * @return 待同步行；正文随行返回，补同步需要重新向量化
     */
    List<MemoryItem> listVectorSyncCandidates(Long agentId, int limit, int maxFailCount, LocalDateTime now);

    /**
     * 找出「MySQL 里还有 LONG 行」的 Agent，用于补同步按 Agent 推进。
     * <p>
     * 口径是<b>曾有 LONG 行</b>，与当前记忆策略开关无关：策略关掉后历史条目仍需补齐向量，
     * 否则重新打开时会缺召回。已删除行不计（它们已是终态），按 agent_id 升序。
     *
     * @return Agent id 列表；没有 LONG 行时为空列表
     */
    List<Long> listLongAgentIds();

    /**
     * 统计待补同步条数（不含到失败上限的行），供运维接口回显本轮规模。
     *
     * @param maxFailCount 允许重试的最大失败次数
     * @param now          当前时间
     * @return 待同步条数
     */
    int countVectorSyncCandidates(int maxFailCount, LocalDateTime now);

    /**
     * 把「有效 LONG」全部打回 PENDING 并清零失败次数与原因。供<b>受控重建</b>使用：
     * 集合被删除重建后，向量库是空的，但 MySQL 里这些行的状态还是 SYNCED，补同步不会捞到它们。
     * <p>
     * 口径：{@code scope='LONG' AND deleted=0 AND status=1 AND (expired_time IS NULL OR expired_time > now)}；
     * 已删除 / 停用 / 已过期的行不动（它们本就该由删点阶段收口）。
     * 幂等：已经是 PENDING 且失败次数为 0、无原因的行不重复计入。<b>禁止</b>在启动或定时链路里调用。
     *
     * @param now 当前时间（过期判定）
     * @return 实际重排的行数
     */
    int requeueValidLong(LocalDateTime now);
}
