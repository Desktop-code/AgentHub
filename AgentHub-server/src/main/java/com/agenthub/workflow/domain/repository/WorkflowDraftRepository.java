package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.WorkflowDraft;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 工作流草稿仓储。所属域：workflow 的 domain。
 * <p>一个定义只有一行草稿；更新使用 update_time 做乐观锁（可选）。
 */
public interface WorkflowDraftRepository {

    /**
     * @param workflowId 定义 id
     * @return 草稿；不存在为空
     */
    Optional<WorkflowDraft> findByWorkflowId(Long workflowId);

    /**
     * @param draft      新草稿
     * @param operatorId 创建人
     */
    void insert(WorkflowDraft draft, Long operatorId);

    /**
     * 写草稿图。expectedUpdateTime 非空时作为 CAS 条件。
     *
     * @param workflowId         定义 id
     * @param graphJson          图 JSON
     * @param expectedUpdateTime 期望的更新时间；null 表示不校验
     * @param operatorId         更新人
     * @return 实际更新行数；0 表示并发冲突或草稿不存在
     */
    int updateGraph(Long workflowId, String graphJson, LocalDateTime expectedUpdateTime, Long operatorId);
}
