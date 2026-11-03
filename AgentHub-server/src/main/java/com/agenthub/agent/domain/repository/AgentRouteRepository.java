package com.agenthub.agent.domain.repository;

import com.agenthub.agent.domain.model.AgentRoute;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Router 子路由仓储。所属域：agent。同一父节点与同一子节点在未删范围内只能有一行。
 */
public interface AgentRouteRepository {

    /**
     * @param parentAgentId 父 Agent 主键
     * @return 未删除路由，sort_order 小的在前
     */
    List<AgentRoute> listByParentId(Long parentAgentId);

    /**
     * @param id 路由主键
     * @return 未删除路由
     */
    Optional<AgentRoute> findById(Long id);

    /**
     * @param parentAgentId 父 Agent 主键
     * @param childAgentId  子 Agent 主键
     * @param excludeId     更新时排除当前行；新建传 null
     * @return true 表示已有未删除的同一对
     */
    boolean existsPair(Long parentAgentId, Long childAgentId, Long excludeId);

    /**
     * 同父下 route_key 是否已被其它未删行占用（大小写敏感按库值比对）。
     *
     * @param parentAgentId 父 Agent 主键
     * @param routeKey      非空白路由键
     * @param excludeId     更新时排除当前行；新建传 null
     * @return true 表示冲突
     */
    boolean existsRouteKey(Long parentAgentId, String routeKey, Long excludeId);

    /**
     * @param parentAgentId 父 Agent 主键
     * @return 同父未删行最大 sort_order；无子返回 empty
     */
    Optional<Integer> maxSortOrder(Long parentAgentId);

    /**
     * @param parentAgentId 父 Agent 主键
     * @return 未删除子路由数
     */
    int countByParentId(Long parentAgentId);

    /**
     * 多智能体列表的「候选数」列：只数启用中的子路由，停用子不计入参与者。
     * <p>按批量 id 一次聚合，避免列表逐行 count 造成 N+1。
     *
     * @param parentAgentIds 父 Agent 主键集合，可空
     * @return parentAgentId → 启用中的未删除子路由数；没有路由的 id 不出现在结果里
     */
    Map<Long, Integer> countEnabledByParentIds(Collection<Long> parentAgentIds);

    /**
     * @param route      新路由
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(AgentRoute route, Long operatorId);

    /**
     * 改路由键、意图、排序和启停。不改父子关系。
     *
     * @param route      已存在的路由
     * @param operatorId 更新人
     */
    void update(AgentRoute route, Long operatorId);

    /**
     * @param id         路由主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);

    /**
     * 删除 Agent 时，它作为父节点或子节点的路由一起逻辑删除。
     *
     * @param agentId    Agent 主键
     * @param operatorId 操作人
     */
    void logicalDeleteByAgentId(Long agentId, Long operatorId);
}
