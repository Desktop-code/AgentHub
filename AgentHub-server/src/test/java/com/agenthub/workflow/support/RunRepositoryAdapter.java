package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 内存 run 仓储适配器。所属域：workflow 的 test support。
 * <p>只做方法签名对接，数据在 {@link InMemoryWorkflowStore}。
 */
class RunRepositoryAdapter implements WorkflowRunRepository {

    private final InMemoryWorkflowStore store;

    RunRepositoryAdapter(InMemoryWorkflowStore store) {
        this.store = store;
    }

    @Override
    public Long insert(WorkflowRun run) {
        return store.insertRun(run);
    }

    @Override
    public Optional<WorkflowRun> findById(Long id) {
        return store.findRun(id);
    }

    @Override
    public boolean updateStatusIfMatches(Long id, String expectFrom, String to, String errorSummary,
                                         String outputJson, LocalDateTime endTime, Long operatorId) {
        return store.updateRunStatusIfMatches(id, expectFrom, to, errorSummary, outputJson, endTime, operatorId);
    }

    @Override
    public void updateStatus(Long id, String to, String errorSummary, String outputJson,
                             LocalDateTime endTime, Long operatorId) {
        store.updateRunStatus(id, to, errorSummary, outputJson, endTime, operatorId);
    }

    @Override
    public void updateRunLimits(Long id, Long runLimitMs, Long nodeLimitMs, LocalDateTime runDeadline,
                                LocalDateTime waitingSince, Long operatorId) {
        store.updateRunLimits(id, runLimitMs, nodeLimitMs, runDeadline, waitingSince, operatorId);
    }

    @Override
    public List<WorkflowRun> listByWorkflowId(Long workflowId, int limit) {
        return store.listRuns(workflowId, limit);
    }

    @Override
    public List<Long> recentIdsByWorkflowId(Long workflowId, int limit) {
        return store.listRuns(workflowId, limit).stream().map(WorkflowRun::getId).toList();
    }

    @Override
    public void attachTraceId(Long id, String traceId) {
        // 内存实现只改 trace_id 一个字段，语义对齐 MySQL 的单列 update
        store.findRun(id).ifPresent(run -> run.setTraceId(traceId));
    }

    @Override
    public Optional<WorkflowRun> findByAppAndClientRequestId(Long appId, String clientRequestId) {
        return store.findRunByAppAndClientRequestId(appId, clientRequestId);
    }

    /**
     * {@inheritDoc}：内存实现按与 SQL 相同的口径过滤——同一工作流 + 同一工作台会话，
     * 状态为 RUNNING / PAUSED_HUMAN / WAITING_REPLY；多条时取 id 最大的一条（最新）。
     */
    @Override
    public Optional<WorkflowRun> findActiveByWorkbenchSession(Long workflowId, Long workbenchSessionId) {
        if (workflowId == null || workbenchSessionId == null) {
            return Optional.empty();
        }
        return store.listRuns(workflowId, Integer.MAX_VALUE).stream()
                .filter(run -> workbenchSessionId.equals(run.getWorkbenchSessionId()))
                .filter(run -> "RUNNING".equals(run.getStatus()) || "PAUSED_HUMAN".equals(run.getStatus())
                        || "WAITING_REPLY".equals(run.getStatus()))
                .max(java.util.Comparator.comparing(WorkflowRun::getId, java.util.Comparator.nullsFirst(Long::compareTo)));
    }

    /**
     * {@inheritDoc}：内存实现只认等待回复态，按 id 取最新一条。
     */
    @Override
    public Optional<WorkflowRun> findWaitingReplyByWorkbenchSession(Long workbenchSessionId) {
        return store.findWaitingReplyRun(workbenchSessionId);
    }

    /** {@inheritDoc}：只改 input_json 一列。 */
    @Override
    public void updateInput(Long id, String inputJson, Long operatorId) {
        store.updateRunInput(id, inputJson);
    }

    /**
     * {@inheritDoc}：按会话取全部未结束 run，内存实现直接复用存储的过滤。
     */
    @Override
    public List<WorkflowRun> listActiveByWorkbenchSession(Long workbenchSessionId) {
        return store.listActiveRunsByWorkbenchSession(workbenchSessionId);
    }
}
