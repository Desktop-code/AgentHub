package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.persistence.entity.AgentToolBindEntity;
import com.agenthub.agent.infra.persistence.mapper.AgentToolBindMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工具绑定仓储实现。所属域：agent。Entity 不出本包。
 */
@Repository
public class AgentToolBindRepositoryImpl implements AgentToolBindRepository {

    private final AgentToolBindMapper mapper;

    /**
     * @param mapper agent_tool_bind
     */
    public AgentToolBindRepositoryImpl(AgentToolBindMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentToolBind> listByAgentId(Long agentId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentToolBindEntity>()
                        .eq(AgentToolBindEntity::getAgentId, agentId)
                        .orderByAsc(AgentToolBindEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentToolBind> findById(Long id) {
        AgentToolBindEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsPair(Long agentId, Long toolItemId, Long excludeId) {
        LambdaQueryWrapper<AgentToolBindEntity> wrapper = new LambdaQueryWrapper<AgentToolBindEntity>()
                .eq(AgentToolBindEntity::getAgentId, agentId)
                .eq(AgentToolBindEntity::getToolItemId, toolItemId);
        if (excludeId != null) {
            wrapper.ne(AgentToolBindEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public int countByAgentId(Long agentId) {
        return Math.toIntExact(mapper.selectCount(new LambdaQueryWrapper<AgentToolBindEntity>()
                .eq(AgentToolBindEntity::getAgentId, agentId)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(AgentToolBind bind, Long operatorId) {
        AgentToolBindEntity entity = new AgentToolBindEntity();
        entity.setAgentId(bind.getAgentId());
        entity.setToolItemId(bind.getToolItemId());
        entity.setStatus(bind.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(AgentToolBind bind, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentToolBindEntity>()
                .eq(AgentToolBindEntity::getId, bind.getId())
                .set(AgentToolBindEntity::getStatus, bind.getStatus())
                .set(AgentToolBindEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentToolBindEntity>()
                .eq(AgentToolBindEntity::getId, id)
                .set(AgentToolBindEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDeleteByAgentId(Long agentId, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentToolBindEntity>()
                .eq(AgentToolBindEntity::getAgentId, agentId)
                .set(AgentToolBindEntity::getUpdatedBy, operatorId));
        mapper.delete(new LambdaQueryWrapper<AgentToolBindEntity>()
                .eq(AgentToolBindEntity::getAgentId, agentId));
    }

    private AgentToolBind toDomain(AgentToolBindEntity entity) {
        AgentToolBind bind = new AgentToolBind();
        bind.setId(entity.getId());
        bind.setAgentId(entity.getAgentId());
        bind.setToolItemId(entity.getToolItemId());
        bind.setStatus(entity.getStatus());
        bind.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        bind.setCreateTime(entity.getCreateTime());
        bind.setUpdatedBy(entity.getUpdatedBy());
        bind.setUpdateTime(entity.getUpdateTime());
        return bind;
    }
}
