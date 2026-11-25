package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 内存 human_task 仓储适配器。所属域：workflow 的 test support。
 */
class HumanTaskRepositoryAdapter implements WorkflowHumanTaskRepository {

    private final InMemoryWorkflowStore store;

    HumanTaskRepositoryAdapter(InMemoryWorkflowStore store) {
        this.store = store;
    }

    @Override
    public Long insert(WorkflowHumanTask task) {
        return store.insertTask(task);
    }

    @Override
    public Optional<WorkflowHumanTask> findById(Long id) {
        return store.findTask(id);
    }

    @Override
    public List<WorkflowHumanTask> listOpenByRunId(Long runId) {
        return store.listOpenByRunId(runId);
    }

    @Override
    public List<WorkflowHumanTask> listExpiredOpen(Long runId, LocalDateTime now) {
        return store.listExpiredOpen(runId, now);
    }

    @Override
    public List<WorkflowHumanTask> listByTenant(Long tenantId, String status, int limit) {
        return store.listTasksByTenant(tenantId, status, limit);
    }

    @Override
    public boolean decide(Long id, String status, String resultJson, Long decidedBy, Long operatorId) {
        return store.decideTask(id, status, resultJson, decidedBy, operatorId);
    }
}
