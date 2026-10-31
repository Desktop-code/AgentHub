package com.agenthub.agent.domain.repository;

import com.agenthub.agent.domain.model.AgentToolBind;

import java.util.List;
import java.util.Optional;

/**
 * 工具绑定仓储。所属域：agent。同一 Agent 与同一工具项在未删范围内只能有一行。
 */
public interface AgentToolBindRepository {

    /**
     * @param agentId Agent 主键
     * @return 未删除绑定
     */
    List<AgentToolBind> listByAgentId(Long agentId);

    /**
     * @param id 绑定主键
     * @return 未删除绑定
     */
    Optional<AgentToolBind> findById(Long id);

    /**
     * @param agentId    Agent 主键
     * @param toolItemId 工具项主键
     * @param excludeId  更新时排除当前行；新建传 null
     * @return true 表示已有未删除的同一对
     */
    boolean existsPair(Long agentId, Long toolItemId, Long excludeId);

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
    Long insert(AgentToolBind bind, Long operatorId);

    /**
     * @param bind       已存在的绑定，只改启停
     * @param operatorId 更新人
     */
    void update(AgentToolBind bind, Long operatorId);

    /**
     * @param id         绑定主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);

    /**
     * @param agentId    Agent 主键
     * @param operatorId 操作人
     */
    void logicalDeleteByAgentId(Long agentId, Long operatorId);
}
