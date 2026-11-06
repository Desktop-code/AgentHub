package com.agenthub.workflow.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.infra.persistence.entity.WorkflowRunEntity;
import com.agenthub.workflow.infra.persistence.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 运行实例仓储实现。所属域：workflow 的 infra。
 * <p>状态写入只走 LambdaUpdateWrapper 显式列，避免把整行（含 graph_json 大字段）重写一遍。
 */
@Repository
public class WorkflowRunRepositoryImpl implements WorkflowRunRepository {

    private final WorkflowRunMapper mapper;

    /**
     * @param mapper workflow_run
     */
    public WorkflowRunRepositoryImpl(WorkflowRunMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(WorkflowRun run) {
        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setTenantId(run.getTenantId());
        entity.setWorkflowId(run.getWorkflowId());
        entity.setReleaseId(run.getReleaseId());
        entity.setReleaseVersion(run.getReleaseVersion());
        entity.setFingerprint(run.getFingerprint());
        entity.setTraceId(run.getTraceId());
        entity.setStatus(run.getStatus());
        entity.setTriggerType(run.getTriggerType());
        entity.setTriggeredBy(run.getTriggeredBy());
        entity.setAppId(run.getAppId());
        entity.setClientRequestId(run.getClientRequestId());
        entity.setInputJson(run.getInputJson());
        entity.setOutputJson(run.getOutputJson());
        entity.setErrorSummary(run.getErrorSummary());
        entity.setGraphJson(run.getGraphJson());
        entity.setStartTime(run.getStartTime());
        entity.setEndTime(run.getEndTime());
        entity.setCreatedBy(run.getCreatedBy());
        entity.setUpdatedBy(run.getCreatedBy());
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowRun> findById(Long id) {
        WorkflowRunEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateStatusIfMatches(Long id, String expectFrom, String to, String errorSummary,
                                         String outputJson, LocalDateTime endTime, Long operatorId) {
        LambdaUpdateWrapper<WorkflowRunEntity> wrapper = new LambdaUpdateWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getId, id)
                .eq(WorkflowRunEntity::getStatus, expectFrom)
                .set(WorkflowRunEntity::getStatus, to)
                .set(WorkflowRunEntity::getErrorSummary, errorSummary)
                .set(WorkflowRunEntity::getUpdatedBy, operatorId)
                .set(WorkflowRunEntity::getUpdateTime, LocalDateTime.now());
        if (outputJson != null) {
            wrapper.set(WorkflowRunEntity::getOutputJson, outputJson);
        }
        if (endTime != null) {
            wrapper.set(WorkflowRunEntity::getEndTime, endTime);
        }
        return mapper.update(null, wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(Long id, String to, String errorSummary, String outputJson,
                             LocalDateTime endTime, Long operatorId) {
        LambdaUpdateWrapper<WorkflowRunEntity> wrapper = new LambdaUpdateWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getId, id)
                .set(WorkflowRunEntity::getStatus, to)
                .set(WorkflowRunEntity::getErrorSummary, errorSummary)
                .set(WorkflowRunEntity::getUpdatedBy, operatorId)
                .set(WorkflowRunEntity::getUpdateTime, LocalDateTime.now());
        if (outputJson != null) {
            wrapper.set(WorkflowRunEntity::getOutputJson, outputJson);
        }
        if (endTime != null) {
            wrapper.set(WorkflowRunEntity::getEndTime, endTime);
        }
        mapper.update(null, wrapper);
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowRun> listByWorkflowId(Long workflowId, int limit) {
        int size = (int) PageQuery.clampSize(limit, 50);
        return mapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                        .eq(WorkflowRunEntity::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowRunEntity::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> recentIdsByWorkflowId(Long workflowId, int limit) {
        // 只查 id 列：obs 按定义过滤 trace 时不需要 graph_json 等大字段
        int size = (int) PageQuery.clampSize(limit, 500);
        return mapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                        .select(WorkflowRunEntity::getId)
                        .eq(WorkflowRunEntity::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowRunEntity::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(WorkflowRunEntity::getId)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public void attachTraceId(Long id, String traceId) {
        if (id == null || traceId == null || traceId.isBlank()) {
            return;
        }
        // 只写 trace_id：不碰 status / end_time，避免与调度线程的状态迁移互相覆盖
        mapper.update(null, new LambdaUpdateWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getId, id)
                .set(WorkflowRunEntity::getTraceId, traceId)
                .set(WorkflowRunEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowRun> findByAppAndClientRequestId(Long appId, String clientRequestId) {
        if (appId == null || clientRequestId == null || clientRequestId.isBlank()) {
            return Optional.empty();
        }
        WorkflowRunEntity entity = mapper.selectOne(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getAppId, appId)
                .eq(WorkflowRunEntity::getClientRequestId, clientRequestId)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    private WorkflowRun toDomain(WorkflowRunEntity entity) {
        WorkflowRun run = new WorkflowRun();
        run.setId(entity.getId());
        run.setTenantId(entity.getTenantId());
        run.setWorkflowId(entity.getWorkflowId());
        run.setReleaseId(entity.getReleaseId());
        run.setReleaseVersion(entity.getReleaseVersion());
        run.setFingerprint(entity.getFingerprint());
        run.setTraceId(entity.getTraceId());
        run.setStatus(entity.getStatus());
        run.setTriggerType(entity.getTriggerType());
        run.setTriggeredBy(entity.getTriggeredBy());
        run.setAppId(entity.getAppId());
        run.setClientRequestId(entity.getClientRequestId());
        run.setInputJson(entity.getInputJson());
        run.setOutputJson(entity.getOutputJson());
        run.setErrorSummary(entity.getErrorSummary());
        run.setGraphJson(entity.getGraphJson());
        run.setStartTime(entity.getStartTime());
        run.setEndTime(entity.getEndTime());
        run.setCreatedBy(entity.getCreatedBy());
        run.setCreateTime(entity.getCreateTime());
        run.setUpdatedBy(entity.getUpdatedBy());
        run.setUpdateTime(entity.getUpdateTime());
        return run;
    }
}
