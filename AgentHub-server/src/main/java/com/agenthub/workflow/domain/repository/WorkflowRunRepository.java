package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.WorkflowRun;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 运行实例仓储。所属域：workflow 的 domain。
 * <p>状态写入必须走 {@link #updateStatus}（不带状态判断）或
 * {@link #updateStatusIfMatches}（乐观迁移）；禁止提供「整行 update」把状态当普通字段改。
 */
public interface WorkflowRunRepository {

    /**
     * @param run 新实例（需带 graphJson / releaseId / status）
     * @return 新主键
     */
    Long insert(WorkflowRun run);

    /**
     * @param id 主键
     * @return 实例；不存在为空
     */
    Optional<WorkflowRun> findById(Long id);

    /**
     * 条件迁移：仅当库里当前状态等于 expectFrom 时写入新状态。
     *
     * @param id          主键
     * @param expectFrom  期望的当前状态（防并发双写）
     * @param to          目标状态（调用方已过状态机）
     * @param errorSummary 失败 / 取消摘要；可空
     * @param outputJson  终态输出；可空（为空表示不改）
     * @param endTime     终态时间；可空（为空表示不改）
     * @param operatorId  操作人
     * @return true 表示实际写入了一行
     */
    boolean updateStatusIfMatches(Long id, String expectFrom, String to, String errorSummary,
                                  String outputJson, LocalDateTime endTime, Long operatorId);

    /**
     * 强制写状态（供引擎内部的正常迁移；调用方仍须先过状态机）。
     *
     * @param id           主键
     * @param to           目标状态
     * @param errorSummary 摘要；null 表示清空
     * @param outputJson   输出；null 表示不改
     * @param endTime      终态时间；null 表示不改
     * @param operatorId   操作人
     */
    void updateStatus(Long id, String to, String errorSummary, String outputJson,
                      LocalDateTime endTime, Long operatorId);

    /**
     * 某个工作流最近的 run，按 id 倒序。
     *
     * @param workflowId 定义 id
     * @param limit      条数上限
     * @return 列表
     */
    List<WorkflowRun> listByWorkflowId(Long workflowId, int limit);

    /**
     * 某工作流最近若干次 run 的 id，按 id 倒序。供 obs 按定义过滤 trace 用（只回 id，不加载整行）。
     *
     * @param workflowId 定义 id
     * @param limit      条数上限；实现方钳制在 [1, 500]
     * @return id 列表；无数据为空列表
     */
    List<Long> recentIdsByWorkflowId(Long workflowId, int limit);

    /**
     * 绑定 obs 根 trace。只写 trace_id 一列，不参与状态机，也不触发状态迁移。
     *
     * @param id      run 主键
     * @param traceId 业务 traceId
     */
    void attachTraceId(Long id, String traceId);

    /**
     * 应用触发的幂等查询：同 app 下同一 clientRequestId 只应有一个 run。
     *
     * @param appId           应用 id
     * @param clientRequestId 调用方幂等键
     * @return 已存在的 run；无则为空
     */
    Optional<WorkflowRun> findByAppAndClientRequestId(Long appId, String clientRequestId);

    /**
     * 工作台防重查询：同一会话 + 同一工作流是否还有未结束的 run（RUNNING / PAUSED_HUMAN）。
     *
     * <p>为什么按库查而不是只靠内存：工作台一轮是同步等待，人工卡点会把 run 停在
     * PAUSED_HUMAN 跨请求存活，只有库里的状态能跨请求判重。
     *
     * @param workflowId        工作流定义 id
     * @param workbenchSessionId 工作台会话 id
     * @return 最近一条未结束 run；没有为空
     */
    Optional<WorkflowRun> findActiveByWorkbenchSession(Long workflowId, Long workbenchSessionId);
}
