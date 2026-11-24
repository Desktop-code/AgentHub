package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.WorkflowNodeRun;

import java.util.Collection;
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
     * 批量取多个 run 的节点行（对话回放补全用）。
     *
     * <p>为什么需要批量：一次刷新可能带同一条会话里的多张工作流卡，
     * 逐卡查库是 N 次往返；这里一次取回再按 runId 分组。
     *
     * @param runIds run 主键集合；可空
     * @return 按主键升序的节点行；没有时为空表
     */
    List<WorkflowNodeRun> listByRunIds(Collection<Long> runIds);

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

    /**
     * 记录用户对某个询问节点的回复（对话续跑）。
     *
     * <p>为什么单独一条更新、不并进 {@link #update(WorkflowNodeRun)}：引擎每节点迁移都会整行
     * 回写状态 / 产出，若把回复也放进那条更新，未带该字段的调用点会把已写的回复洗成 null。
     * 这里只在续跑入口显式写一次，语义单一。
     *
     * @param runId     run 主键
     * @param nodeCode  节点编码（同一 run 内唯一）
     * @param replyText 用户回复（调用方已截断）
     * @return 受影响行数
     */
    int markReply(Long runId, String nodeCode, String replyText);
}
