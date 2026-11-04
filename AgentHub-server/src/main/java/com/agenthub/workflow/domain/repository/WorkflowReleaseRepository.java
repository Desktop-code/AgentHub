package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.WorkflowRelease;

import java.util.List;
import java.util.Optional;

/**
 * 工作流发布版本仓储。所属域：workflow 的 domain。
 * <p>只允许追加；禁止提供更新历史 graph_json 的方法。
 */
public interface WorkflowReleaseRepository {

    /**
     * @param workflowId 定义 id
     * @return 当前最大版本号；没有版本时返回 0
     */
    int maxVersion(Long workflowId);

    /**
     * @param release 版本快照
     * @return 新主键
     */
    Long insert(WorkflowRelease release);

    /**
     * @param id 主键
     * @return 版本；不存在为空
     */
    Optional<WorkflowRelease> findById(Long id);

    /**
     * @param workflowId 定义 id
     * @return 版本列表，按 version 倒序
     */
    List<WorkflowRelease> listByWorkflowId(Long workflowId);
}
