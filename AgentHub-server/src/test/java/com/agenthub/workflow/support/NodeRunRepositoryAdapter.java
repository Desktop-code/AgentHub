package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;

import java.util.List;
import java.util.Optional;

/**
 * 内存 node_run 仓储适配器。所属域：workflow 的 test support。
 */
class NodeRunRepositoryAdapter implements WorkflowNodeRunRepository {

    private final InMemoryWorkflowStore store;

    NodeRunRepositoryAdapter(InMemoryWorkflowStore store) {
        this.store = store;
    }

    @Override
    public void insertBatch(List<WorkflowNodeRun> nodeRuns) {
        store.insertNodeRuns(nodeRuns);
    }

    @Override
    public List<WorkflowNodeRun> listByRunId(Long runId) {
        return store.listByRunId(runId);
    }

    @Override
    public List<WorkflowNodeRun> listByRunIds(java.util.Collection<Long> runIds) {
        return store.listByRunIds(runIds);
    }

    @Override
    public Optional<WorkflowNodeRun> findById(Long id) {
        return store.findNodeRun(id);
    }

    @Override
    public void update(WorkflowNodeRun nodeRun) {
        store.updateNodeRun(nodeRun);
    }

    @Override
    public int cancelOpen(Long runId) {
        return store.cancelOpen(runId);
    }

    @Override
    public int markReply(Long runId, String nodeCode, String replyText) {
        return store.markReply(runId, nodeCode, replyText);
    }
}
