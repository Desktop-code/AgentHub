package com.agenthub.memory.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.persistence.entity.MemoryItemEntity;
import com.agenthub.memory.infra.persistence.mapper.MemoryItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆条目仓储实现。所属域：memory。Entity 不出本包。
 */
@Repository
public class MemoryItemRepositoryImpl implements MemoryItemRepository {

    private final MemoryItemMapper mapper;

    /**
     * @param mapper memory_item
     */
    public MemoryItemRepositoryImpl(MemoryItemMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * lifeStatus=DELETED 走自定义 SQL（绕过 TableLogic），其余走 Wrapper。
     */
    @Override
    public PageResult<MemoryItem> page(MemoryQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        String life = query.getLifeStatus() == null ? "" : query.getLifeStatus().trim().toUpperCase();

        // 已删除行被 TableLogic 默认滤掉，必须用显式 deleted=1 的分页 SQL
        if ("DELETED".equals(life)) {
            long total = mapper.countDeleted(query);
            long offset = (current - 1) * size;
            List<MemoryItemEntity> rows = mapper.pageDeleted(query, offset, size);
            return PageResult.of(total, current, size, rows.stream().map(this::toDomain).toList());
        }

        LambdaQueryWrapper<MemoryItemEntity> wrapper = new LambdaQueryWrapper<MemoryItemEntity>()
                .orderByDesc(MemoryItemEntity::getId);
        applyCommonFilters(wrapper, query);
        applyLifeStatus(wrapper, life, query.getStatus());
        IPage<MemoryItemEntity> result = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords().stream().map(this::toDomain).toList());
    }

    /**
     * Agent / scope / session / user / 创建时间：与是否查已删无关的公共条件。
     */
    private void applyCommonFilters(LambdaQueryWrapper<MemoryItemEntity> wrapper, MemoryQuery query) {
        if (query.getAgentId() != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, query.getAgentId());
        }
        if (query.getScope() != null && !query.getScope().isBlank()) {
            wrapper.eq(MemoryItemEntity::getScope, query.getScope().trim());
        }
        if (query.getSessionId() != null) {
            wrapper.eq(MemoryItemEntity::getSessionId, query.getSessionId());
        }
        if (query.getUserId() != null) {
            wrapper.eq(MemoryItemEntity::getUserId, query.getUserId());
        }
        if (query.getCreateTimeFrom() != null) {
            wrapper.ge(MemoryItemEntity::getCreateTime, query.getCreateTimeFrom());
        }
        if (query.getCreateTimeTo() != null) {
            wrapper.le(MemoryItemEntity::getCreateTime, query.getCreateTimeTo());
        }
    }

    /**
     * NORMAL=启用且未过期；EXPIRED=expired_time 已到；空则回退到旧 status 字段。
     */
    private void applyLifeStatus(LambdaQueryWrapper<MemoryItemEntity> wrapper, String life, Integer status) {
        LocalDateTime now = LocalDateTime.now();
        if ("NORMAL".equals(life)) {
            wrapper.eq(MemoryItemEntity::getStatus, 1)
                    .and(one -> one.isNull(MemoryItemEntity::getExpiredTime)
                            .or()
                            .gt(MemoryItemEntity::getExpiredTime, now));
            return;
        }
        if ("EXPIRED".equals(life)) {
            wrapper.isNotNull(MemoryItemEntity::getExpiredTime)
                    .le(MemoryItemEntity::getExpiredTime, now);
            return;
        }
        if (status != null) {
            wrapper.eq(MemoryItemEntity::getStatus, status);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryItem> findById(Long id) {
        MemoryItemEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryItem> findActiveByHash(Long agentId, Long userId, String scope, String hash) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now())
                .eq(MemoryItemEntity::getContentHash, hash)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT 1");
        eqOrNull(wrapper, agentId, userId);
        MemoryItemEntity entity = mapper.selectOne(wrapper);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryItem> findActiveByFactKey(Long agentId, Long userId, String scope, String factKey) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now())
                .eq(MemoryItemEntity::getFactKey, factKey)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT 1");
        eqOrNull(wrapper, agentId, userId);
        MemoryItemEntity entity = mapper.selectOne(wrapper);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> listActiveShort(Long agentId, Long sessionId, LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active("SHORT", now)
                .eq(MemoryItemEntity::getAgentId, agentId)
                .eq(MemoryItemEntity::getSessionId, sessionId)
                .orderByAsc(MemoryItemEntity::getId);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit, LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, now)
                .orderByDesc(MemoryItemEntity::getImportance)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT " + Math.max(limit, 1));
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        if ("LONG".equals(scope)) {
            eqUser(wrapper, userId);
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public int countActive(Long agentId, Long userId, String scope) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now());
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        if ("LONG".equals(scope)) {
            eqUser(wrapper, userId);
        }
        Long count = mapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /**
     * {@inheritDoc}
     * 先留 importance 高且更新的，其余逻辑删除。
     */
    @Override
    public void evictOverflow(Long agentId, Long userId, String scope, int keep, Long operatorId) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now())
                .orderByDesc(MemoryItemEntity::getImportance)
                .orderByDesc(MemoryItemEntity::getId);
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        if ("LONG".equals(scope)) {
            eqUser(wrapper, userId);
        }
        List<MemoryItemEntity> rows = mapper.selectList(wrapper);
        int retain = Math.max(keep, 0);
        for (int i = retain; i < rows.size(); i++) {
            mapper.deleteById(rows.get(i).getId());
        }
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(MemoryItem item, Long operatorId) {
        MemoryItemEntity entity = new MemoryItemEntity();
        copy(item, entity);
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
    public void update(MemoryItem item, Long operatorId) {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId(item.getId());
        copy(item, entity);
        entity.setUpdatedBy(operatorId);
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public int purgeExpired(LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = new LambdaQueryWrapper<MemoryItemEntity>()
                .isNotNull(MemoryItemEntity::getExpiredTime)
                .lt(MemoryItemEntity::getExpiredTime, now);
        return mapper.delete(wrapper);
    }

    private LambdaQueryWrapper<MemoryItemEntity> active(String scope, LocalDateTime now) {
        return new LambdaQueryWrapper<MemoryItemEntity>()
                .eq(MemoryItemEntity::getScope, scope)
                .eq(MemoryItemEntity::getStatus, 1)
                .and(one -> one.isNull(MemoryItemEntity::getExpiredTime)
                        .or()
                        .gt(MemoryItemEntity::getExpiredTime, now));
    }

    private void eqOrNull(LambdaQueryWrapper<MemoryItemEntity> wrapper, Long agentId, Long userId) {
        if (agentId == null) {
            wrapper.isNull(MemoryItemEntity::getAgentId);
        } else {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        eqUser(wrapper, userId);
    }

    private void eqUser(LambdaQueryWrapper<MemoryItemEntity> wrapper, Long userId) {
        if (userId == null) {
            wrapper.isNull(MemoryItemEntity::getUserId);
        } else {
            wrapper.eq(MemoryItemEntity::getUserId, userId);
        }
    }

    private void copy(MemoryItem item, MemoryItemEntity entity) {
        entity.setAgentId(item.getAgentId());
        entity.setUserId(item.getUserId());
        entity.setAppId(item.getAppId());
        entity.setSessionId(item.getSessionId());
        entity.setScope(item.getScope());
        entity.setTitle(item.getTitle());
        entity.setFactKey(item.getFactKey());
        entity.setContent(item.getContent());
        entity.setContentHash(item.getContentHash());
        entity.setImportance(item.getImportance() == null ? 0 : item.getImportance());
        entity.setStatus(item.getStatus() == null ? 1 : item.getStatus());
        entity.setExpiredTime(item.getExpiredTime());
        entity.setRemark(item.getRemark());
    }

    private MemoryItem toDomain(MemoryItemEntity entity) {
        MemoryItem item = new MemoryItem();
        item.setId(entity.getId());
        item.setAgentId(entity.getAgentId());
        item.setUserId(entity.getUserId());
        item.setAppId(entity.getAppId());
        item.setSessionId(entity.getSessionId());
        item.setScope(entity.getScope());
        item.setTitle(entity.getTitle());
        item.setFactKey(entity.getFactKey());
        item.setContent(entity.getContent());
        item.setContentHash(entity.getContentHash());
        item.setImportance(entity.getImportance());
        item.setStatus(entity.getStatus());
        item.setExpiredTime(entity.getExpiredTime());
        item.setRemark(entity.getRemark());
        item.setCreatedBy(entity.getCreatedBy());
        item.setCreateTime(entity.getCreateTime());
        item.setUpdatedBy(entity.getUpdatedBy());
        item.setUpdateTime(entity.getUpdateTime());
        return item;
    }
}
