package com.agenthub.workflow.infra.persistence;

import com.agenthub.workflow.domain.model.WorkflowDraft;
import com.agenthub.workflow.domain.repository.WorkflowDraftRepository;
import com.agenthub.workflow.infra.persistence.entity.WorkflowDefDraftEntity;
import com.agenthub.workflow.infra.persistence.mapper.WorkflowDefDraftMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 工作流草稿仓储实现。所属域：workflow 的 infra。
 * <p>update_time 既作审计列也作乐观锁：expectedUpdateTime 非空时必须在 WHERE 里匹配。
 */
@Repository
public class WorkflowDraftRepositoryImpl implements WorkflowDraftRepository {

    private final WorkflowDefDraftMapper mapper;

    /**
     * @param mapper workflow_def_draft
     */
    public WorkflowDraftRepositoryImpl(WorkflowDefDraftMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowDraft> findByWorkflowId(Long workflowId) {
        WorkflowDefDraftEntity entity = mapper.selectById(workflowId);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public void insert(WorkflowDraft draft, Long operatorId) {
        WorkflowDefDraftEntity entity = new WorkflowDefDraftEntity();
        entity.setWorkflowId(draft.getWorkflowId());
        entity.setGraphJson(draft.getGraphJson());
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        mapper.insert(entity);
    }

    /** {@inheritDoc} */
    @Override
    public int updateGraph(Long workflowId, String graphJson,
                           LocalDateTime expectedUpdateTime, Long operatorId) {
        LambdaUpdateWrapper<WorkflowDefDraftEntity> wrapper =
                new LambdaUpdateWrapper<WorkflowDefDraftEntity>()
                        .eq(WorkflowDefDraftEntity::getWorkflowId, workflowId)
                        .set(WorkflowDefDraftEntity::getGraphJson, graphJson)
                        .set(WorkflowDefDraftEntity::getUpdatedBy, operatorId)
                        // 显式写时间：同内容重复保存也要推进版本，便于乐观锁比较
                        .set(WorkflowDefDraftEntity::getUpdateTime, LocalDateTime.now());
        if (expectedUpdateTime != null) {
            wrapper.eq(WorkflowDefDraftEntity::getUpdateTime, expectedUpdateTime);
        }
        return mapper.update(null, wrapper);
    }

    private WorkflowDraft toDomain(WorkflowDefDraftEntity entity) {
        WorkflowDraft draft = new WorkflowDraft();
        draft.setWorkflowId(entity.getWorkflowId());
        draft.setGraphJson(entity.getGraphJson());
        draft.setCreatedBy(entity.getCreatedBy());
        draft.setCreateTime(entity.getCreateTime());
        draft.setUpdatedBy(entity.getUpdatedBy());
        draft.setUpdateTime(entity.getUpdateTime());
        return draft;
    }
}
