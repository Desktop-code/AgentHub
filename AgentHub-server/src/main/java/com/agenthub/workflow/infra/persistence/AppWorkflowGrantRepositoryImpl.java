package com.agenthub.workflow.infra.persistence;

import com.agenthub.workflow.domain.model.AppWorkflowGrant;
import com.agenthub.workflow.domain.repository.AppWorkflowGrantRepository;
import com.agenthub.workflow.infra.persistence.entity.AppWorkflowGrantEntity;
import com.agenthub.workflow.infra.persistence.mapper.AppWorkflowGrantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用-工作流授权仓储实现。所属域：workflow 的 infra。
 * <p>对齐策略：先读现有行 → 删除不在目标集合的行 → 只对新增行 insert。
 * 这样已钉死的 {@code release_id} 在控制台重复保存后不会丢。
 */
@Repository
public class AppWorkflowGrantRepositoryImpl implements AppWorkflowGrantRepository {

    private final AppWorkflowGrantMapper mapper;

    /**
     * @param mapper 授权表
     */
    public AppWorkflowGrantRepositoryImpl(AppWorkflowGrantMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<AppWorkflowGrant> listByAppId(Long appId) {
        List<AppWorkflowGrantEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<AppWorkflowGrantEntity>()
                        .eq(AppWorkflowGrantEntity::getAppId, appId)
                        .orderByAsc(AppWorkflowGrantEntity::getId));
        List<AppWorkflowGrant> grants = new ArrayList<>(rows.size());
        for (AppWorkflowGrantEntity row : rows) {
            grants.add(toDomain(row));
        }
        return grants;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AppWorkflowGrant> findEnabled(Long appId, Long workflowDefId) {
        if (appId == null || workflowDefId == null) {
            return Optional.empty();
        }
        AppWorkflowGrantEntity row = mapper.selectOne(new LambdaQueryWrapper<AppWorkflowGrantEntity>()
                .eq(AppWorkflowGrantEntity::getAppId, appId)
                .eq(AppWorkflowGrantEntity::getWorkflowDefId, workflowDefId)
                .eq(AppWorkflowGrantEntity::getStatus, 1)
                .last("LIMIT 1"));
        return Optional.ofNullable(row).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public void replace(Long appId, Collection<Long> workflowDefIds, Long operatorId) {
        Map<Long, AppWorkflowGrant> existing = new HashMap<>();
        for (AppWorkflowGrant grant : listByAppId(appId)) {
            existing.put(grant.getWorkflowDefId(), grant);
        }
        // 先删「不在目标集合」的授权：前端取消勾选即撤销
        for (Map.Entry<Long, AppWorkflowGrant> entry : existing.entrySet()) {
            if (workflowDefIds == null || !workflowDefIds.contains(entry.getKey())) {
                mapper.deleteById(entry.getValue().getId());
            }
        }
        if (workflowDefIds == null) {
            return;
        }
        for (Long workflowDefId : workflowDefIds) {
            // 已存在则不动：保留 release_id 钉死状态
            if (existing.containsKey(workflowDefId)) {
                continue;
            }
            AppWorkflowGrantEntity entity = new AppWorkflowGrantEntity();
            entity.setAppId(appId);
            entity.setWorkflowDefId(workflowDefId);
            entity.setReleaseId(null);
            entity.setStatus(1);
            entity.setCreatedBy(operatorId);
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdatedBy(operatorId);
            entity.setUpdateTime(LocalDateTime.now());
            mapper.insert(entity);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByAppId(Long appId) {
        mapper.delete(new LambdaQueryWrapper<AppWorkflowGrantEntity>()
                .eq(AppWorkflowGrantEntity::getAppId, appId));
    }

    private AppWorkflowGrant toDomain(AppWorkflowGrantEntity entity) {
        AppWorkflowGrant grant = new AppWorkflowGrant();
        grant.setId(entity.getId());
        grant.setAppId(entity.getAppId());
        grant.setWorkflowDefId(entity.getWorkflowDefId());
        grant.setReleaseId(entity.getReleaseId());
        grant.setStatus(entity.getStatus());
        grant.setCreatedBy(entity.getCreatedBy());
        grant.setCreateTime(entity.getCreateTime());
        grant.setUpdatedBy(entity.getUpdatedBy());
        grant.setUpdateTime(entity.getUpdateTime());
        return grant;
    }
}
