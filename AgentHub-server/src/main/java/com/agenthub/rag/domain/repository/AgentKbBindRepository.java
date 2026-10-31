package com.agenthub.rag.domain.repository;

import com.agenthub.rag.domain.model.AgentKbBind;

import java.util.List;
import java.util.Optional;

/**
 * Agent 知识库绑定仓储。所属域：rag。
 */
public interface AgentKbBindRepository {

    /**
     * @param agentId Agent
     * @return 该 Agent 的绑定，按 id 升序
     */
    List<AgentKbBind> listByAgentId(Long agentId);

    /**
     * @param id 主键
     * @return 绑定
     */
    Optional<AgentKbBind> findById(Long id);

    /**
     * 同一 Agent 与同一 KB 是否已绑过。
     *
     * @param agentId   Agent
     * @param kbId      知识库
     * @param excludeId 更新时排除自身；新建传 null
     * @return true 表示已存在
     */
    boolean existsPair(Long agentId, Long kbId, Long excludeId);

    /**
     * @param kbId 知识库
     * @return 该库被多少 Agent 绑定（含停用），删除知识库前做提示
     */
    int countByKbId(Long kbId);

    /**
     * @param bind       绑定
     * @param operatorId 操作人
     * @return 新主键
     */
    Long insert(AgentKbBind bind, Long operatorId);

    /**
     * 改启停与覆盖项。
     *
     * @param bind       绑定
     * @param operatorId 操作人
     */
    void update(AgentKbBind bind, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);

    /**
     * 知识库被删时清理其全部绑定。
     *
     * @param kbId       知识库
     * @param operatorId 操作人
     */
    void logicalDeleteByKbId(Long kbId, Long operatorId);
}
