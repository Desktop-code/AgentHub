package com.agenthub.memory.domain.repository;

import com.agenthub.memory.domain.model.MemoryPolicy;

import java.util.Optional;

/**
 * 记忆策略仓储。所属域：memory。每个 Agent 只应有一条未删除且启用的策略。
 */
public interface MemoryPolicyRepository {

    /**
     * @param agentId Agent 主键
     * @return 最新一条有效策略
     */
    Optional<MemoryPolicy> findActiveByAgentId(Long agentId);

    /**
     * @param id 主键
     * @return 未删除策略
     */
    Optional<MemoryPolicy> findById(Long id);

    /**
     * @param policy     新策略
     * @param operatorId 操作人
     * @return 新主键
     */
    Long insert(MemoryPolicy policy, Long operatorId);

    /**
     * @param policy     已存在的策略
     * @param operatorId 操作人
     */
    void update(MemoryPolicy policy, Long operatorId);
}
