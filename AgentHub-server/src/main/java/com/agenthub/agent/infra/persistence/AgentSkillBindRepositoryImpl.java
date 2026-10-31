package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.AgentSkillBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.infra.persistence.entity.AgentSkillBindEntity;
import com.agenthub.agent.infra.persistence.mapper.AgentSkillBindMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Skill 绑定仓储实现。所属域：agent。Entity 不出本包。
 */
@Repository
public class AgentSkillBindRepositoryImpl implements AgentSkillBindRepository {

    private final AgentSkillBindMapper mapper;

    /**
     * @param mapper agent_skill_bind
     */
    public AgentSkillBindRepositoryImpl(AgentSkillBindMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentSkillBind> listByAgentId(Long agentId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentSkillBindEntity>()
                        .eq(AgentSkillBindEntity::getAgentId, agentId)
                        .orderByAsc(AgentSkillBindEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentSkillBind> findById(Long id) {
        AgentSkillBindEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsPair(Long agentId, Long skillId, Long excludeId) {
        LambdaQueryWrapper<AgentSkillBindEntity> wrapper = new LambdaQueryWrapper<AgentSkillBindEntity>()
                .eq(AgentSkillBindEntity::getAgentId, agentId)
                .eq(AgentSkillBindEntity::getSkillId, skillId);
        if (excludeId != null) {
            wrapper.ne(AgentSkillBindEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public int countByAgentId(Long agentId) {
        return Math.toIntExact(mapper.selectCount(new LambdaQueryWrapper<AgentSkillBindEntity>()
                .eq(AgentSkillBindEntity::getAgentId, agentId)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(AgentSkillBind bind, Long operatorId) {
        AgentSkillBindEntity entity = new AgentSkillBindEntity();
        entity.setAgentId(bind.getAgentId());
        entity.setSkillId(bind.getSkillId());
        entity.setStatus(bind.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(AgentSkillBind bind, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentSkillBindEntity>()
                .eq(AgentSkillBindEntity::getId, bind.getId())
                .set(AgentSkillBindEntity::getStatus, bind.getStatus())
                .set(AgentSkillBindEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentSkillBindEntity>()
                .eq(AgentSkillBindEntity::getId, id)
                .set(AgentSkillBindEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDeleteByAgentId(Long agentId, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentSkillBindEntity>()
                .eq(AgentSkillBindEntity::getAgentId, agentId)
                .set(AgentSkillBindEntity::getUpdatedBy, operatorId));
        mapper.delete(new LambdaQueryWrapper<AgentSkillBindEntity>()
                .eq(AgentSkillBindEntity::getAgentId, agentId));
    }

    private AgentSkillBind toDomain(AgentSkillBindEntity entity) {
        AgentSkillBind bind = new AgentSkillBind();
        bind.setId(entity.getId());
        bind.setAgentId(entity.getAgentId());
        bind.setSkillId(entity.getSkillId());
        bind.setStatus(entity.getStatus());
        bind.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        bind.setCreateTime(entity.getCreateTime());
        bind.setUpdatedBy(entity.getUpdatedBy());
        bind.setUpdateTime(entity.getUpdateTime());
        return bind;
    }
}
