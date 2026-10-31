package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.infra.persistence.entity.AgentModelBindEntity;
import com.agenthub.agent.infra.persistence.mapper.AgentModelBindMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 模型绑定仓储实现。所属域：agent。Entity 不出本包。
 */
@Repository
public class AgentModelBindRepositoryImpl implements AgentModelBindRepository {

    private final AgentModelBindMapper mapper;

    /**
     * @param mapper agent_model_bind
     */
    public AgentModelBindRepositoryImpl(AgentModelBindMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentModelBind> listByAgentId(Long agentId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentModelBindEntity>()
                        .eq(AgentModelBindEntity::getAgentId, agentId)
                        .orderByAsc(AgentModelBindEntity::getPriority)
                        .orderByAsc(AgentModelBindEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentModelBind> findById(Long id) {
        AgentModelBindEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsPair(Long agentId, Long modelInstanceId, Long excludeId) {
        LambdaQueryWrapper<AgentModelBindEntity> wrapper = new LambdaQueryWrapper<AgentModelBindEntity>()
                .eq(AgentModelBindEntity::getAgentId, agentId)
                .eq(AgentModelBindEntity::getModelInstanceId, modelInstanceId);
        if (excludeId != null) {
            wrapper.ne(AgentModelBindEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public int countByAgentId(Long agentId) {
        return Math.toIntExact(mapper.selectCount(new LambdaQueryWrapper<AgentModelBindEntity>()
                .eq(AgentModelBindEntity::getAgentId, agentId)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(AgentModelBind bind, Long operatorId) {
        AgentModelBindEntity entity = new AgentModelBindEntity();
        entity.setAgentId(bind.getAgentId());
        entity.setModelInstanceId(bind.getModelInstanceId());
        entity.setPurpose(bind.getPurpose());
        entity.setPromptTemplateId(bind.getPromptTemplateId());
        entity.setTemplateVersion(bind.getTemplateVersion());
        entity.setPromptOverrideJson(bind.getPromptOverrideJson());
        entity.setPromptExtra(bind.getPromptExtra());
        entity.setPriority(bind.getPriority());
        entity.setStatus(bind.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(AgentModelBind bind, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentModelBindEntity>()
                .eq(AgentModelBindEntity::getId, bind.getId())
                .set(AgentModelBindEntity::getPurpose, bind.getPurpose())
                .set(AgentModelBindEntity::getPromptTemplateId, bind.getPromptTemplateId())
                .set(AgentModelBindEntity::getTemplateVersion, bind.getTemplateVersion())
                .set(AgentModelBindEntity::getPromptOverrideJson, bind.getPromptOverrideJson())
                .set(AgentModelBindEntity::getPromptExtra, bind.getPromptExtra())
                .set(AgentModelBindEntity::getPriority, bind.getPriority())
                .set(AgentModelBindEntity::getStatus, bind.getStatus())
                .set(AgentModelBindEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentModelBindEntity>()
                .eq(AgentModelBindEntity::getId, id)
                .set(AgentModelBindEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDeleteByAgentId(Long agentId, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentModelBindEntity>()
                .eq(AgentModelBindEntity::getAgentId, agentId)
                .set(AgentModelBindEntity::getUpdatedBy, operatorId));
        mapper.delete(new LambdaQueryWrapper<AgentModelBindEntity>()
                .eq(AgentModelBindEntity::getAgentId, agentId));
    }

    private AgentModelBind toDomain(AgentModelBindEntity entity) {
        AgentModelBind bind = new AgentModelBind();
        bind.setId(entity.getId());
        bind.setAgentId(entity.getAgentId());
        bind.setModelInstanceId(entity.getModelInstanceId());
        bind.setPurpose(entity.getPurpose());
        bind.setPromptTemplateId(entity.getPromptTemplateId());
        bind.setTemplateVersion(entity.getTemplateVersion());
        bind.setPromptOverrideJson(entity.getPromptOverrideJson());
        bind.setPromptExtra(entity.getPromptExtra());
        bind.setPriority(entity.getPriority());
        bind.setStatus(entity.getStatus());
        bind.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        bind.setCreateTime(entity.getCreateTime());
        bind.setUpdatedBy(entity.getUpdatedBy());
        bind.setUpdateTime(entity.getUpdateTime());
        return bind;
    }
}
