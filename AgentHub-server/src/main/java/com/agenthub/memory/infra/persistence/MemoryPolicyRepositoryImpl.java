package com.agenthub.memory.infra.persistence;

import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.MemoryPolicyRepository;
import com.agenthub.memory.infra.persistence.entity.MemoryPolicyEntity;
import com.agenthub.memory.infra.persistence.mapper.MemoryPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 记忆策略仓储实现。所属域：memory。同一 Agent 多行时取最新一条启用记录。
 */
@Repository
public class MemoryPolicyRepositoryImpl implements MemoryPolicyRepository {

    private final MemoryPolicyMapper mapper;

    /**
     * @param mapper memory_agent_policy
     */
    public MemoryPolicyRepositoryImpl(MemoryPolicyMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryPolicy> findActiveByAgentId(Long agentId) {
        MemoryPolicyEntity entity = mapper.selectOne(new LambdaQueryWrapper<MemoryPolicyEntity>()
                .eq(MemoryPolicyEntity::getAgentId, agentId)
                .eq(MemoryPolicyEntity::getStatus, 1)
                .orderByDesc(MemoryPolicyEntity::getId)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryPolicy> findById(Long id) {
        MemoryPolicyEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(MemoryPolicy policy, Long operatorId) {
        MemoryPolicyEntity entity = new MemoryPolicyEntity();
        copy(policy, entity);
        entity.setCreatedBy(operatorId);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdatedBy(operatorId);
        entity.setUpdateTime(entity.getCreateTime());
        entity.setDeleted(0);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(MemoryPolicy policy, Long operatorId) {
        MemoryPolicyEntity entity = new MemoryPolicyEntity();
        entity.setId(policy.getId());
        copy(policy, entity);
        entity.setUpdatedBy(operatorId);
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
    }

    private void copy(MemoryPolicy policy, MemoryPolicyEntity entity) {
        entity.setAgentId(policy.getAgentId());
        entity.setEnableShort(flag(policy.getEnableShort(), 1));
        entity.setEnableLong(flag(policy.getEnableLong(), 0));
        entity.setMaxShortItems(policy.getMaxShortItems());
        entity.setMaxLongItems(policy.getMaxLongItems());
        entity.setCtxMaxTurns(policy.getCtxMaxTurns());
        entity.setCtxMaxChars(policy.getCtxMaxChars());
        entity.setCtxIdleTtlSec(policy.getCtxIdleTtlSec());
        entity.setShortDefaultTtlSec(policy.getShortDefaultTtlSec());
        entity.setSummaryModelInstanceId(policy.getSummaryModelInstanceId());
        entity.setEnableLongExtract(flag(policy.getEnableLongExtract(), 1));
        entity.setLongExtractModelInstanceId(policy.getLongExtractModelInstanceId());
        entity.setLongExtractOnSessionEnd(flag(policy.getLongExtractOnSessionEnd(), 1));
        entity.setLongExtractOnExplicit(flag(policy.getLongExtractOnExplicit(), 1));
        entity.setStatus(flag(policy.getStatus(), 1));
        entity.setRemark(policy.getRemark());
    }

    private MemoryPolicy toDomain(MemoryPolicyEntity entity) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setId(entity.getId());
        policy.setAgentId(entity.getAgentId());
        policy.setEnableShort(entity.getEnableShort());
        policy.setEnableLong(entity.getEnableLong());
        policy.setMaxShortItems(entity.getMaxShortItems());
        policy.setMaxLongItems(entity.getMaxLongItems());
        policy.setCtxMaxTurns(entity.getCtxMaxTurns());
        policy.setCtxMaxChars(entity.getCtxMaxChars());
        policy.setCtxIdleTtlSec(entity.getCtxIdleTtlSec());
        policy.setShortDefaultTtlSec(entity.getShortDefaultTtlSec());
        policy.setSummaryModelInstanceId(entity.getSummaryModelInstanceId());
        policy.setEnableLongExtract(entity.getEnableLongExtract());
        policy.setLongExtractModelInstanceId(entity.getLongExtractModelInstanceId());
        policy.setLongExtractOnSessionEnd(entity.getLongExtractOnSessionEnd());
        policy.setLongExtractOnExplicit(entity.getLongExtractOnExplicit());
        policy.setStatus(entity.getStatus());
        policy.setRemark(entity.getRemark());
        policy.setCreatedBy(entity.getCreatedBy());
        policy.setCreateTime(entity.getCreateTime());
        policy.setUpdatedBy(entity.getUpdatedBy());
        policy.setUpdateTime(entity.getUpdateTime());
        return policy;
    }

    private static int flag(Integer raw, int fallback) {
        return raw == null ? fallback : raw;
    }
}
