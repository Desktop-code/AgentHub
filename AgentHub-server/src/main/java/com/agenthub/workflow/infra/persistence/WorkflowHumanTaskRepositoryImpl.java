package com.agenthub.workflow.infra.persistence;

import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.infra.persistence.entity.WorkflowHumanTaskEntity;
import com.agenthub.workflow.infra.persistence.mapper.WorkflowHumanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 人工待办仓储实现。所属域：workflow 的 infra。
 * <p>decide 带 status='OPEN' 条件：重复点击 / 超时与人工同时到达时只有一方生效。
 */
@Repository
public class WorkflowHumanTaskRepositoryImpl implements WorkflowHumanTaskRepository {

    private final WorkflowHumanTaskMapper mapper;

    /**
     * @param mapper workflow_human_task
     */
    public WorkflowHumanTaskRepositoryImpl(WorkflowHumanTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(WorkflowHumanTask task) {
        WorkflowHumanTaskEntity entity = new WorkflowHumanTaskEntity();
        entity.setTenantId(task.getTenantId());
        entity.setRunId(task.getRunId());
        entity.setNodeRunId(task.getNodeRunId());
        entity.setNodeCode(task.getNodeCode());
        entity.setStatus(task.getStatus());
        entity.setAssigneeRule(task.getAssigneeRule());
        entity.setTimeoutPolicy(task.getTimeoutPolicy());
        entity.setFormJson(task.getFormJson());
        entity.setResultJson(task.getResultJson());
        entity.setExpireTime(task.getExpireTime());
        entity.setCreatedBy(task.getCreatedBy());
        entity.setUpdatedBy(task.getCreatedBy());
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowHumanTask> findById(Long id) {
        WorkflowHumanTaskEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowHumanTask> listOpenByRunId(Long runId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowHumanTaskEntity>()
                        .eq(WorkflowHumanTaskEntity::getRunId, runId)
                        .eq(WorkflowHumanTaskEntity::getStatus, "OPEN")
                        .orderByAsc(WorkflowHumanTaskEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowHumanTask> listExpiredOpen(Long runId, LocalDateTime now) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowHumanTaskEntity>()
                        .eq(WorkflowHumanTaskEntity::getRunId, runId)
                        .eq(WorkflowHumanTaskEntity::getStatus, "OPEN")
                        .isNotNull(WorkflowHumanTaskEntity::getExpireTime)
                        .le(WorkflowHumanTaskEntity::getExpireTime, now)
                        .orderByAsc(WorkflowHumanTaskEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowHumanTask> listByTenant(Long tenantId, String status, int limit) {
        // OPEN 优先：待办中心的默认视图就是「还没处理的」，其余状态按时间倒序归档
        int size = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<WorkflowHumanTaskEntity> wrapper = new LambdaQueryWrapper<WorkflowHumanTaskEntity>()
                .eq(WorkflowHumanTaskEntity::getTenantId, tenantId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(WorkflowHumanTaskEntity::getStatus, status.trim().toUpperCase());
        }
        wrapper.orderByDesc(WorkflowHumanTaskEntity::getId).last("LIMIT " + size);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public boolean decide(Long id, String status, String resultJson, Long decidedBy, Long operatorId) {
        return mapper.update(null, new LambdaUpdateWrapper<WorkflowHumanTaskEntity>()
                .eq(WorkflowHumanTaskEntity::getId, id)
                .eq(WorkflowHumanTaskEntity::getStatus, "OPEN")
                .set(WorkflowHumanTaskEntity::getStatus, status)
                .set(WorkflowHumanTaskEntity::getResultJson, resultJson)
                .set(WorkflowHumanTaskEntity::getDecidedBy, decidedBy)
                .set(WorkflowHumanTaskEntity::getDecidedTime, LocalDateTime.now())
                .set(WorkflowHumanTaskEntity::getUpdatedBy, operatorId)
                .set(WorkflowHumanTaskEntity::getUpdateTime, LocalDateTime.now())) > 0;
    }

    private WorkflowHumanTask toDomain(WorkflowHumanTaskEntity entity) {
        WorkflowHumanTask task = new WorkflowHumanTask();
        task.setId(entity.getId());
        task.setTenantId(entity.getTenantId());
        task.setRunId(entity.getRunId());
        task.setNodeRunId(entity.getNodeRunId());
        task.setNodeCode(entity.getNodeCode());
        task.setStatus(entity.getStatus());
        task.setAssigneeRule(entity.getAssigneeRule());
        task.setTimeoutPolicy(entity.getTimeoutPolicy());
        task.setFormJson(entity.getFormJson());
        task.setResultJson(entity.getResultJson());
        task.setExpireTime(entity.getExpireTime());
        task.setDecidedBy(entity.getDecidedBy());
        task.setDecidedTime(entity.getDecidedTime());
        task.setCreatedBy(entity.getCreatedBy());
        task.setCreateTime(entity.getCreateTime());
        task.setUpdatedBy(entity.getUpdatedBy());
        task.setUpdateTime(entity.getUpdateTime());
        return task;
    }
}
