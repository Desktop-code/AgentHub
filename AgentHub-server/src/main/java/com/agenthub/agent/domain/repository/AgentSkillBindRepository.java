package com.agenthub.agent.domain.repository;

import com.agenthub.agent.domain.model.AgentSkillBind;

import java.util.List;
import java.util.Optional;

/**
 * Skill 绑定仓储。所属域：agent。同一 Agent 与同一 Skill 在未删范围内只能有一行。
 */
public interface AgentSkillBindRepository {

    /**
     * @param agentId Agent 主键
     * @return 未删除绑定
     */
    List<AgentSkillBind> listByAgentId(Long agentId);

    /**
     * @param id 绑定主键
     * @return 未删除绑定
     */
    Optional<AgentSkillBind> findById(Long id);

    /**
     * @param agentId   Agent 主键
     * @param skillId   Skill 主键
     * @param excludeId 更新时排除当前行；新建传 null
     * @return true 表示已有未删除的同一对
     */
    boolean existsPair(Long agentId, Long skillId, Long excludeId);

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
    Long insert(AgentSkillBind bind, Long operatorId);

    /**
     * @param bind       已存在的绑定，只改启停
     * @param operatorId 更新人
     */
    void update(AgentSkillBind bind, Long operatorId);

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
