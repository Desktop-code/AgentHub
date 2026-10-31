package com.agenthub.agent.domain.repository;

import com.agenthub.agent.domain.model.AgentModelBind;

import java.util.List;
import java.util.Optional;

/**
 * 模型绑定仓储。所属域：agent。同一 Agent 与同一实例在未删范围内只能有一行。
 */
public interface AgentModelBindRepository {

    /**
     * @param agentId Agent 主键
     * @return 未删除绑定，priority 小的在前
     */
    List<AgentModelBind> listByAgentId(Long agentId);

    /**
     * @param id 绑定主键
     * @return 未删除绑定
     */
    Optional<AgentModelBind> findById(Long id);

    /**
     * @param agentId         Agent 主键
     * @param modelInstanceId 模型实例主键
     * @param excludeId       更新时排除当前行；新建传 null
     * @return true 表示已有未删除的同一对
     */
    boolean existsPair(Long agentId, Long modelInstanceId, Long excludeId);

    /**
     * @param agentId Agent 主键
     * @return 未删除绑定数
     */
    int countByAgentId(Long agentId);

    /**
     * @param bind       新绑定
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(AgentModelBind bind, Long operatorId);

    /**
     * 改用途、优先级和启停。
     *
     * @param bind       已存在的绑定
     * @param operatorId 更新人
     */
    void update(AgentModelBind bind, Long operatorId);

    /**
     * @param id         绑定主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);

    /**
     * 删除 Agent 时带走它的模型绑定。表上没有外键，由调用方放进同一事务。
     *
     * @param agentId    Agent 主键
     * @param operatorId 操作人
     */
    void logicalDeleteByAgentId(Long agentId, Long operatorId);
}
