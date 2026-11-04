package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.WorkflowNodeRun;

import java.util.List;
import java.util.Optional;

/**
 * 节点运行态仓储。所属域：workflow 的 domain。
 * <p>建 run 时批量插入 PENDING；之后每次迁移只更新本行，不做整表重写。
 */
public interface WorkflowNodeRunRepository {

    /**
     * @param nodeRuns 预建的 PENDING 行
     */
    void insertBatch(List<WorkflowNodeRun> nodeRuns);

    /**
     * @param runId run 主键
     * @return 按主键升序（即建 run 时的图顺序）
     */
    List<WorkflowNodeRun> listByRunId(Long runId);

    /**
     * @param id 主键
     * @return 行；不存在为空
     */
    Optional<WorkflowNodeRun> findById(Long id);

    /**
     * 写回一行运行态（状态 / 输入 / 输出 / 错误 / 选中边 / 起止时间）。
     *
     * @param nodeRun 已改好字段的行
     */
    void update(WorkflowNodeRun nodeRun);

    /**
     * 取消收口：RUNNING / WAITING_HUMAN → CANCELLED，PENDING → SKIPPED。
     *
     * @param runId run 主键
     * @return 受影响行数
     */
    int cancelOpen(Long runId);
}
