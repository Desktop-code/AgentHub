package com.agenthub.rag.infra.persistence;

import com.agenthub.rag.domain.model.AgentKbBind;
import com.agenthub.rag.domain.repository.AgentKbBindRepository;
import com.agenthub.rag.infra.persistence.entity.AgentKbBindEntity;
import com.agenthub.rag.infra.persistence.mapper.AgentKbBindMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 知识库绑定仓储实现。所属域：rag 的 infra。Entity 不出本包。
 */
@Repository
public class AgentKbBindRepositoryImpl implements AgentKbBindRepository {

    private final AgentKbBindMapper mapper;

    /**
     * @param mapper agent_kb_bind
     */
    public AgentKbBindRepositoryImpl(AgentKbBindMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentKbBind> listByAgentId(Long agentId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentKbBindEntity>()
                        .eq(AgentKbBindEntity::getAgentId, agentId)
                        .orderByAsc(AgentKbBindEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentKbBind> findById(Long id) {
        AgentKbBindEntity entity = id == null ? null : mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsPair(Long agentId, Long kbId, Long excludeId) {
        LambdaQueryWrapper<AgentKbBindEntity> wrapper = new LambdaQueryWrapper<AgentKbBindEntity>()
                .eq(AgentKbBindEntity::getAgentId, agentId)
                .eq(AgentKbBindEntity::getKbId, kbId);
        if (excludeId != null) {
            wrapper.ne(AgentKbBindEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public int countByKbId(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return Math.toIntExact(mapper.selectCount(
                new LambdaQueryWrapper<AgentKbBindEntity>().eq(AgentKbBindEntity::getKbId, kbId)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(AgentKbBind bind, Long operatorId) {
        AgentKbBindEntity entity = new AgentKbBindEntity();
        entity.setTenantId(bind.getTenantId() == null ? 0L : bind.getTenantId());
        entity.setAgentId(bind.getAgentId());
        entity.setKbId(bind.getKbId());
        entity.setStatus(bind.getStatus());
        entity.setOverridesJson(bind.getOverridesJson());
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(AgentKbBind bind, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentKbBindEntity>()
                .eq(AgentKbBindEntity::getId, bind.getId())
                .set(AgentKbBindEntity::getStatus, bind.getStatus())
                .set(AgentKbBindEntity::getOverridesJson, bind.getOverridesJson())
                .set(AgentKbBindEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentKbBindEntity>()
                .eq(AgentKbBindEntity::getId, id)
                .set(AgentKbBindEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDeleteByKbId(Long kbId, Long operatorId) {
        if (kbId == null) {
            return;
        }
        mapper.update(null, new LambdaUpdateWrapper<AgentKbBindEntity>()
                .eq(AgentKbBindEntity::getKbId, kbId)
                .set(AgentKbBindEntity::getUpdatedBy, operatorId));
        mapper.delete(new LambdaQueryWrapper<AgentKbBindEntity>()
                .eq(AgentKbBindEntity::getKbId, kbId));
    }

    private AgentKbBind toDomain(AgentKbBindEntity entity) {
        AgentKbBind bind = new AgentKbBind();
        bind.setId(entity.getId());
        bind.setTenantId(entity.getTenantId());
        bind.setAgentId(entity.getAgentId());
        bind.setKbId(entity.getKbId());
        bind.setStatus(entity.getStatus());
        bind.setOverridesJson(entity.getOverridesJson());
        bind.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        bind.setCreateTime(entity.getCreateTime());
        bind.setUpdatedBy(entity.getUpdatedBy());
        bind.setUpdateTime(entity.getUpdateTime());
        return bind;
    }
}
