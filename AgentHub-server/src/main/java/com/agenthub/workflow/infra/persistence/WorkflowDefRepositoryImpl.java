package com.agenthub.workflow.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDefQuery;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.infra.persistence.entity.WorkflowDefEntity;
import com.agenthub.workflow.infra.persistence.mapper.WorkflowDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 工作流定义仓储实现。所属域：workflow 的 infra。Entity 不出本包。
 * <p>租户过滤：列表强制带 tenant_id；按 id 查询故意不带（应用层区分 404 与越权）；
 * 所有写操作额外带 tenant_id，避免应用层判断漏掉时被越权改。
 */
@Repository
public class WorkflowDefRepositoryImpl implements WorkflowDefRepository {

    private final WorkflowDefMapper mapper;

    /**
     * @param mapper workflow_def
     */
    public WorkflowDefRepositoryImpl(WorkflowDefMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<WorkflowDef> page(WorkflowDefQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        LambdaQueryWrapper<WorkflowDefEntity> wrapper = new LambdaQueryWrapper<WorkflowDefEntity>()
                .eq(WorkflowDefEntity::getTenantId, query.getTenantId())
                .orderByDesc(WorkflowDefEntity::getId);
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(WorkflowDefEntity::getStatus, query.getStatus().trim());
        }
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            // 关键词同时扫 code 与中文名，方便运营检索
            wrapper.and(one -> one.like(WorkflowDefEntity::getCode, keyword)
                    .or()
                    .like(WorkflowDefEntity::getName, keyword));
        }
        IPage<WorkflowDefEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowDef> findById(Long id) {
        WorkflowDefEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowDef> findByTenantAndCode(long tenantId, String code) {
        WorkflowDefEntity entity = mapper.selectOne(new LambdaQueryWrapper<WorkflowDefEntity>()
                .eq(WorkflowDefEntity::getTenantId, tenantId)
                .eq(WorkflowDefEntity::getCode, code));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(WorkflowDef def, Long operatorId) {
        WorkflowDefEntity entity = new WorkflowDefEntity();
        entity.setTenantId(def.getTenantId());
        entity.setCode(def.getCode());
        entity.setName(def.getName());
        entity.setDescription(def.getDescription());
        entity.setStatus(def.getStatus());
        entity.setDefaultReleaseId(def.getDefaultReleaseId());
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateMeta(WorkflowDef def, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<WorkflowDefEntity>()
                .eq(WorkflowDefEntity::getId, def.getId())
                .eq(WorkflowDefEntity::getTenantId, def.getTenantId())
                .set(WorkflowDefEntity::getName, def.getName())
                .set(WorkflowDefEntity::getDescription, def.getDescription())
                .set(WorkflowDefEntity::getUpdatedBy, operatorId)
                // 显式写时间：内容相同也要刷新「最后操作」口径
                .set(WorkflowDefEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public void markPublished(Long id, long tenantId, Long releaseId, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<WorkflowDefEntity>()
                .eq(WorkflowDefEntity::getId, id)
                .eq(WorkflowDefEntity::getTenantId, tenantId)
                .set(WorkflowDefEntity::getStatus, "HAS_RELEASE")
                .set(WorkflowDefEntity::getDefaultReleaseId, releaseId)
                .set(WorkflowDefEntity::getUpdatedBy, operatorId)
                .set(WorkflowDefEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public void touch(Long id, long tenantId, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<WorkflowDefEntity>()
                .eq(WorkflowDefEntity::getId, id)
                .eq(WorkflowDefEntity::getTenantId, tenantId)
                .set(WorkflowDefEntity::getUpdatedBy, operatorId)
                .set(WorkflowDefEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public int softDelete(Long id, long tenantId, Long operatorId) {
        // 显式写 deleted=1 与审计列：@TableLogic 的 deleteById 不会顺手更新 updated_by
        return mapper.update(null, new LambdaUpdateWrapper<WorkflowDefEntity>()
                .eq(WorkflowDefEntity::getId, id)
                .eq(WorkflowDefEntity::getTenantId, tenantId)
                .set(WorkflowDefEntity::getDeleted, 1)
                .set(WorkflowDefEntity::getUpdatedBy, operatorId)
                .set(WorkflowDefEntity::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * MySQL 默认反斜杠转义 LIKE；这里抹掉用户输入里的通配符含义。
     */
    private String likeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private WorkflowDef toDomain(WorkflowDefEntity entity) {
        WorkflowDef def = new WorkflowDef();
        def.setId(entity.getId());
        def.setTenantId(entity.getTenantId());
        def.setCode(entity.getCode());
        def.setName(entity.getName());
        def.setDescription(entity.getDescription());
        def.setStatus(entity.getStatus());
        def.setDefaultReleaseId(entity.getDefaultReleaseId());
        def.setCreatedBy(entity.getCreatedBy());
        def.setCreateTime(entity.getCreateTime());
        def.setUpdatedBy(entity.getUpdatedBy());
        def.setUpdateTime(entity.getUpdateTime());
        return def;
    }
}
