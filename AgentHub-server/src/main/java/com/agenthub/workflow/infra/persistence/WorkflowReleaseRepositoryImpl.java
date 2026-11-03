package com.agenthub.workflow.infra.persistence;

import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.infra.persistence.entity.WorkflowReleaseEntity;
import com.agenthub.workflow.infra.persistence.mapper.WorkflowReleaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 工作流发布版本仓储实现。所属域：workflow 的 infra。
 * <p>不可变：本类只有 insert / select，没有 update 历史行的方法。
 */
@Repository
public class WorkflowReleaseRepositoryImpl implements WorkflowReleaseRepository {

    private final WorkflowReleaseMapper mapper;

    /**
     * @param mapper workflow_release
     */
    public WorkflowReleaseRepositoryImpl(WorkflowReleaseMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public int maxVersion(Long workflowId) {
        WorkflowReleaseEntity top = mapper.selectOne(new LambdaQueryWrapper<WorkflowReleaseEntity>()
                .eq(WorkflowReleaseEntity::getWorkflowId, workflowId)
                .orderByDesc(WorkflowReleaseEntity::getVersion)
                .last("LIMIT 1"));
        return top == null || top.getVersion() == null ? 0 : top.getVersion();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(WorkflowRelease release) {
        WorkflowReleaseEntity entity = new WorkflowReleaseEntity();
        entity.setWorkflowId(release.getWorkflowId());
        entity.setVersion(release.getVersion());
        entity.setGraphJson(release.getGraphJson());
        entity.setFingerprint(release.getFingerprint());
        entity.setPublishNote(release.getPublishNote());
        entity.setPublishedBy(release.getPublishedBy());
        entity.setPublishTime(release.getPublishTime() == null
                ? LocalDateTime.now() : release.getPublishTime());
        entity.setCreatedBy(release.getCreatedBy());
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowRelease> findById(Long id) {
        WorkflowReleaseEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowRelease> listByWorkflowId(Long workflowId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowReleaseEntity>()
                        .eq(WorkflowReleaseEntity::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowReleaseEntity::getVersion))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private WorkflowRelease toDomain(WorkflowReleaseEntity entity) {
        WorkflowRelease release = new WorkflowRelease();
        release.setId(entity.getId());
        release.setWorkflowId(entity.getWorkflowId());
        release.setVersion(entity.getVersion());
        release.setGraphJson(entity.getGraphJson());
        release.setFingerprint(entity.getFingerprint());
        release.setPublishNote(entity.getPublishNote());
        release.setPublishedBy(entity.getPublishedBy());
        release.setPublishTime(entity.getPublishTime());
        release.setCreatedBy(entity.getCreatedBy());
        release.setCreateTime(entity.getCreateTime());
        return release;
    }
}
