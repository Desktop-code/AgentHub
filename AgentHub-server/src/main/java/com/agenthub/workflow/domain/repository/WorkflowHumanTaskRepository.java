package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.WorkflowHumanTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 人工待办仓储。所属域：workflow 的 domain。
 * <p>「到期扫描」由应用层做：仓储只提供按 run 查 OPEN / 按时间查到期，不做定时任务。
 */
public interface WorkflowHumanTaskRepository {

    /**
     * @param task 新待办
     * @return 新主键
     */
    Long insert(WorkflowHumanTask task);

    /**
     * @param id 主键
     * @return 待办；不存在为空
     */
    Optional<WorkflowHumanTask> findById(Long id);

    /**
     * @param runId run 主键
     * @return 该 run 的 OPEN 待办
     */
    List<WorkflowHumanTask> listOpenByRunId(Long runId);

    /**
     * @param runId run 主键
     * @param now   当前时间
     * @return expire_time <= now 的 OPEN 待办
     */
    List<WorkflowHumanTask> listExpiredOpen(Long runId, LocalDateTime now);

    /**
     * 租户内的待办列表（待办中心用）。
     *
     * @param tenantId 租户
     * @param status   状态过滤；null / 空表示不过滤
     * @param limit    最多返回多少条（实现侧会再夹紧）
     * @return 按主键倒序的待办
     */
    List<WorkflowHumanTask> listByTenant(Long tenantId, String status, int limit);

    /**
     * 写回决定结果。仅当库里仍是 OPEN 才更新，防重复点击双写。
     *
     * @param id        主键
     * @param status    DONE / EXPIRED
     * @param resultJson 结果 JSON
     * @param decidedBy 处理人；自动策略可传 null
     * @param operatorId 操作人
     * @return true 表示本次真的改了一行
     */
    boolean decide(Long id, String status, String resultJson, Long decidedBy, Long operatorId);
}
