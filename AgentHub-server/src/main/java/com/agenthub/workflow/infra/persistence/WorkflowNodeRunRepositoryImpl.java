package com.agenthub.workflow.infra.persistence;

import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.infra.persistence.entity.WorkflowNodeRunEntity;
import com.agenthub.workflow.infra.persistence.mapper.WorkflowNodeRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 节点运行态仓储实现。所属域：workflow 的 infra。
 * <p>取消收口用两条条件更新完成，避免逐行读写。
 */
@Repository
public class WorkflowNodeRunRepositoryImpl implements WorkflowNodeRunRepository {

    private final WorkflowNodeRunMapper mapper;

    /**
     * @param mapper workflow_node_run
     */
    public WorkflowNodeRunRepositoryImpl(WorkflowNodeRunMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public void insertBatch(List<WorkflowNodeRun> nodeRuns) {
        if (nodeRuns == null || nodeRuns.isEmpty()) {
            return;
        }
        for (WorkflowNodeRun nodeRun : nodeRuns) {
            WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
            entity.setRunId(nodeRun.getRunId());
            entity.setNodeId(nodeRun.getNodeId());
            entity.setNodeCode(nodeRun.getNodeCode());
            entity.setNodeType(nodeRun.getNodeType());
            entity.setAttempt(nodeRun.getAttempt() == null ? 1 : nodeRun.getAttempt());
            entity.setStatus(nodeRun.getStatus());
            mapper.insert(entity);
            nodeRun.setId(entity.getId());
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowNodeRun> listByRunId(Long runId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowNodeRunEntity>()
                        .eq(WorkflowNodeRunEntity::getRunId, runId)
                        .orderByAsc(WorkflowNodeRunEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkflowNodeRun> listByRunIds(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        // 一次 IN 查询取回多张卡的节点行：回放补全不做逐卡往返
        return mapper.selectList(new LambdaQueryWrapper<WorkflowNodeRunEntity>()
                        .in(WorkflowNodeRunEntity::getRunId, runIds)
                        .orderByAsc(WorkflowNodeRunEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkflowNodeRun> findById(Long id) {
        WorkflowNodeRunEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public void update(WorkflowNodeRun nodeRun) {
        mapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRunEntity>()
                .eq(WorkflowNodeRunEntity::getId, nodeRun.getId())
                .set(WorkflowNodeRunEntity::getStatus, nodeRun.getStatus())
                .set(WorkflowNodeRunEntity::getInputJson, nodeRun.getInputJson())
                .set(WorkflowNodeRunEntity::getOutputJson, nodeRun.getOutputJson())
                .set(WorkflowNodeRunEntity::getErrorSummary, nodeRun.getErrorSummary())
                .set(WorkflowNodeRunEntity::getSelectedEdges, nodeRun.getSelectedEdges())
                .set(WorkflowNodeRunEntity::getStartTime, nodeRun.getStartTime())
                .set(WorkflowNodeRunEntity::getEndTime, nodeRun.getEndTime()));
    }

    /** {@inheritDoc} */
    @Override
    public int cancelOpen(Long runId) {
        int touched = mapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRunEntity>()
                .eq(WorkflowNodeRunEntity::getRunId, runId)
                .in(WorkflowNodeRunEntity::getStatus, "RUNNING", "WAITING_HUMAN", "WAITING_REPLY")
                .set(WorkflowNodeRunEntity::getStatus, "CANCELLED")
                .set(WorkflowNodeRunEntity::getEndTime, java.time.LocalDateTime.now()));
        touched += mapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRunEntity>()
                .eq(WorkflowNodeRunEntity::getRunId, runId)
                .eq(WorkflowNodeRunEntity::getStatus, "PENDING")
                .set(WorkflowNodeRunEntity::getStatus, "SKIPPED"));
        return touched;
    }

    /**
     * {@inheritDoc}
     *
     * <p>条件更新而不是先读后写：同一 run 只有一个等待节点（入口已校验唯一），
     * 直接按 runId + nodeCode 覆盖，避免与引擎收口形成读改写竞争。
     */
    @Override
    public int markReply(Long runId, String nodeCode, String replyText) {
        if (runId == null || nodeCode == null || nodeCode.isBlank()) {
            return 0;
        }
        return mapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRunEntity>()
                .eq(WorkflowNodeRunEntity::getRunId, runId)
                .eq(WorkflowNodeRunEntity::getNodeCode, nodeCode)
                .set(WorkflowNodeRunEntity::getReplyText, replyText));
    }

    private WorkflowNodeRun toDomain(WorkflowNodeRunEntity entity) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId(entity.getId());
        nodeRun.setRunId(entity.getRunId());
        nodeRun.setNodeId(entity.getNodeId());
        nodeRun.setNodeCode(entity.getNodeCode());
        nodeRun.setNodeType(entity.getNodeType());
        nodeRun.setAttempt(entity.getAttempt());
        nodeRun.setStatus(entity.getStatus());
        nodeRun.setInputJson(entity.getInputJson());
        nodeRun.setOutputJson(entity.getOutputJson());
        nodeRun.setErrorSummary(entity.getErrorSummary());
        // 回复是回放「我答了什么」的唯一来源：随行一起读出，读侧不必再查卡片快照
        nodeRun.setReplyText(entity.getReplyText());
        nodeRun.setSelectedEdges(entity.getSelectedEdges());
        nodeRun.setStartTime(entity.getStartTime());
        nodeRun.setEndTime(entity.getEndTime());
        return nodeRun;
    }
}
