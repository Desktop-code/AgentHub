package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.infra.persistence.entity.AgentRouteEntity;
import com.agenthub.agent.infra.persistence.mapper.AgentRouteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 子路由仓储实现。所属域：agent。Entity 不出本包。
 */
@Repository
public class AgentRouteRepositoryImpl implements AgentRouteRepository {

    private final AgentRouteMapper mapper;

    /**
     * @param mapper agent_route
     */
    public AgentRouteRepositoryImpl(AgentRouteMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentRoute> listByParentId(Long parentAgentId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentRouteEntity>()
                        .eq(AgentRouteEntity::getParentAgentId, parentAgentId)
                        .orderByAsc(AgentRouteEntity::getSortOrder)
                        .orderByAsc(AgentRouteEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentRoute> findById(Long id) {
        AgentRouteEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsPair(Long parentAgentId, Long childAgentId, Long excludeId) {
        LambdaQueryWrapper<AgentRouteEntity> wrapper = new LambdaQueryWrapper<AgentRouteEntity>()
                .eq(AgentRouteEntity::getParentAgentId, parentAgentId)
                .eq(AgentRouteEntity::getChildAgentId, childAgentId);
        if (excludeId != null) {
            wrapper.ne(AgentRouteEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsRouteKey(Long parentAgentId, String routeKey, Long excludeId) {
        LambdaQueryWrapper<AgentRouteEntity> wrapper = new LambdaQueryWrapper<AgentRouteEntity>()
                .eq(AgentRouteEntity::getParentAgentId, parentAgentId)
                .eq(AgentRouteEntity::getRouteKey, routeKey);
        if (excludeId != null) {
            wrapper.ne(AgentRouteEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Integer> maxSortOrder(Long parentAgentId) {
        AgentRouteEntity top = mapper.selectOne(new LambdaQueryWrapper<AgentRouteEntity>()
                .eq(AgentRouteEntity::getParentAgentId, parentAgentId)
                .orderByDesc(AgentRouteEntity::getSortOrder)
                .orderByDesc(AgentRouteEntity::getId)
                .last("LIMIT 1"));
        return top == null || top.getSortOrder() == null ? Optional.empty() : Optional.of(top.getSortOrder());
    }

    /** {@inheritDoc} */
    @Override
    public int countByParentId(Long parentAgentId) {
        return Math.toIntExact(mapper.selectCount(new LambdaQueryWrapper<AgentRouteEntity>()
                .eq(AgentRouteEntity::getParentAgentId, parentAgentId)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(AgentRoute route, Long operatorId) {
        AgentRouteEntity entity = new AgentRouteEntity();
        entity.setParentAgentId(route.getParentAgentId());
        entity.setChildAgentId(route.getChildAgentId());
        entity.setRouteKey(route.getRouteKey());
        entity.setIntent(route.getIntent());
        entity.setSortOrder(route.getSortOrder());
        entity.setStatus(route.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(AgentRoute route, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentRouteEntity>()
                .eq(AgentRouteEntity::getId, route.getId())
                .set(AgentRouteEntity::getRouteKey, route.getRouteKey())
                .set(AgentRouteEntity::getIntent, route.getIntent())
                .set(AgentRouteEntity::getSortOrder, route.getSortOrder())
                .set(AgentRouteEntity::getStatus, route.getStatus())
                .set(AgentRouteEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentRouteEntity>()
                .eq(AgentRouteEntity::getId, id)
                .set(AgentRouteEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /**
     * {@inheritDoc}
     * 父行和子行都要清掉，否则删掉子 Agent 后父路由仍指向已删定义。
     */
    @Override
    public void logicalDeleteByAgentId(Long agentId, Long operatorId) {
        mapper.update(null, touching(agentId).set(AgentRouteEntity::getUpdatedBy, operatorId));
        mapper.delete(touchingQuery(agentId));
    }

    private LambdaUpdateWrapper<AgentRouteEntity> touching(Long agentId) {
        return new LambdaUpdateWrapper<AgentRouteEntity>()
                .and(one -> one.eq(AgentRouteEntity::getParentAgentId, agentId)
                        .or()
                        .eq(AgentRouteEntity::getChildAgentId, agentId));
    }

    private LambdaQueryWrapper<AgentRouteEntity> touchingQuery(Long agentId) {
        return new LambdaQueryWrapper<AgentRouteEntity>()
                .and(one -> one.eq(AgentRouteEntity::getParentAgentId, agentId)
                        .or()
                        .eq(AgentRouteEntity::getChildAgentId, agentId));
    }

    private AgentRoute toDomain(AgentRouteEntity entity) {
        AgentRoute route = new AgentRoute();
        route.setId(entity.getId());
        route.setParentAgentId(entity.getParentAgentId());
        route.setChildAgentId(entity.getChildAgentId());
        route.setRouteKey(entity.getRouteKey());
        route.setIntent(entity.getIntent());
        route.setSortOrder(entity.getSortOrder());
        route.setStatus(entity.getStatus());
        route.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        route.setCreateTime(entity.getCreateTime());
        route.setUpdatedBy(entity.getUpdatedBy());
        route.setUpdateTime(entity.getUpdateTime());
        return route;
    }
}
