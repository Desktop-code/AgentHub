package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowDraft;
import com.agenthub.workflow.domain.repository.WorkflowDraftRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 单测用的空草稿仓储。所属域：workflow 的测试。
 * <p>不按草稿跑的用例只需要占住构造参数，不能在这里写出一份图。
 */
final class EmptyDraftRepository implements WorkflowDraftRepository {

    @Override
    public Optional<WorkflowDraft> findByWorkflowId(Long workflowId) {
        return Optional.empty();
    }

    @Override
    public void insert(WorkflowDraft draft, Long operatorId) {
    }

    /**
     * @return 0，表示没有草稿可写
     */
    @Override
    public int updateGraph(Long workflowId, String graphJson, LocalDateTime expectedUpdateTime, Long operatorId) {
        return 0;
    }
}
