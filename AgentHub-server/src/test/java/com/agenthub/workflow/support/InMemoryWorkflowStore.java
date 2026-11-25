package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowDef;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工作流窄测用的内存数据。所属域：workflow 的 test support。
 * <p>为什么不做成直接实现三个仓储：run / node_run / human_task 都有
 * {@code findById(Long)}，同签名不同返回类型无法在一个类里共存。
 * 因此数据本体放在这里，再由 {@link #runs()} / {@link #nodeRuns()} / {@link #humanTasks()}
 * 暴露三个窄适配器，行为对齐 MySQL 实现（状态条件更新、待办只在 OPEN 时可决定）。
 * <p>只在单测里用；不注册为 Spring Bean，不连库。
 */
public class InMemoryWorkflowStore {

    private final Map<Long, WorkflowRun> runs = new LinkedHashMap<>();
    private final Map<Long, WorkflowNodeRun> nodeRuns = new LinkedHashMap<>();
    private final Map<Long, WorkflowHumanTask> humanTasks = new LinkedHashMap<>();
    private final Map<Long, WorkflowDef> defs = new LinkedHashMap<>();
    private final AtomicLong runSeq = new AtomicLong();
    private final AtomicLong nodeRunSeq = new AtomicLong();
    private final AtomicLong taskSeq = new AtomicLong();

    /**
     * @return run 仓储适配器
     */
    public com.agenthub.workflow.domain.repository.WorkflowRunRepository runs() {
        return new RunRepositoryAdapter(this);
    }

    /**
     * @return node_run 仓储适配器
     */
    public com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository nodeRuns() {
        return new NodeRunRepositoryAdapter(this);
    }

    /**
     * @return human_task 仓储适配器
     */
    public com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository humanTasks() {
        return new HumanTaskRepositoryAdapter(this);
    }

    /**
     * @return 定义仓储适配器（单测只用到 findById）
     */
    public com.agenthub.workflow.domain.repository.WorkflowDefRepository defs() {
        return new DefRepositoryAdapter(this);
    }

    /**
     * 预置一条定义，供待办列表断言工作流名使用。
     *
     * @param def 定义
     */
    public void putDef(WorkflowDef def) {
        defs.put(def.getId(), def);
    }

    /**
     * @param id 定义 id
     * @return 定义；不存在为空
     */
    public Optional<WorkflowDef> findDef(Long id) {
        return Optional.ofNullable(defs.get(id));
    }

    /**
     * @return 全部预置定义（单测用，顺序不保证）
     */
    public List<WorkflowDef> listDefs() {
        return new ArrayList<>(defs.values());
    }

    // ---------------------------------------------------------------------
    // run
    // ---------------------------------------------------------------------

    /**
     * @param run 新 run
     * @return 主键
     */
    public Long insertRun(WorkflowRun run) {
        long id = runSeq.incrementAndGet();
        run.setId(id);
        run.setCreateTime(run.getCreateTime() == null ? LocalDateTime.now() : run.getCreateTime());
        runs.put(id, run);
        return id;
    }

    /**
     * @param id 主键
     * @return run
     */
    public Optional<WorkflowRun> findRun(Long id) {
        return Optional.ofNullable(runs.get(id));
    }

    /**
     * 条件迁移，语义对齐 MySQL 的 {@code WHERE status = expectFrom}。
     */
    public boolean updateRunStatusIfMatches(Long id, String expectFrom, String to, String errorSummary,
                                            String outputJson, LocalDateTime endTime, Long operatorId) {
        WorkflowRun run = runs.get(id);
        if (run == null || !expectFrom.equals(run.getStatus())) {
            return false;
        }
        applyStatus(run, to, errorSummary, outputJson, endTime, operatorId);
        return true;
    }

    /**
     * 强制迁移。
     */
    public void updateRunStatus(Long id, String to, String errorSummary, String outputJson,
                                LocalDateTime endTime, Long operatorId) {
        WorkflowRun run = runs.get(id);
        if (run != null) {
            applyStatus(run, to, errorSummary, outputJson, endTime, operatorId);
        }
    }

    /**
     * 写运行时限 / 截止 / 等待时刻（对齐 MySQL 的单列组更新；null 也照写）。
     */
    public void updateRunLimits(Long id, Long runLimitMs, Long nodeLimitMs, LocalDateTime runDeadline,
                                LocalDateTime waitingSince, Long operatorId) {
        WorkflowRun run = runs.get(id);
        if (run == null) {
            return;
        }
        run.setRunLimitMs(runLimitMs);
        run.setNodeLimitMs(nodeLimitMs);
        run.setRunDeadline(runDeadline);
        run.setWaitingSince(waitingSince);
        run.setUpdatedBy(operatorId);
        run.setUpdateTime(LocalDateTime.now());
    }

    /**
     * @param workflowId 定义 id
     * @param limit      条数
     * @return 倒序列表
     */
    public List<WorkflowRun> listRuns(Long workflowId, int limit) {
        return runs.values().stream()
                .filter(run -> workflowId.equals(run.getWorkflowId()))
                .sorted(Comparator.comparing(WorkflowRun::getId).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 工作台停止时按会话找未结束 run（RUNNING / PAUSED_HUMAN / WAITING_REPLY），id 倒序。
     *
     * @param workbenchSessionId 工作台会话 id
     * @return 未结束 run；没有为空列表
     */
    public List<WorkflowRun> listActiveRunsByWorkbenchSession(Long workbenchSessionId) {
        if (workbenchSessionId == null) {
            return List.of();
        }
        return runs.values().stream()
                .filter(run -> workbenchSessionId.equals(run.getWorkbenchSessionId()))
                .filter(run -> "RUNNING".equals(run.getStatus()) || "PAUSED_HUMAN".equals(run.getStatus())
                        || "WAITING_REPLY".equals(run.getStatus()))
                .sorted(Comparator.comparing(WorkflowRun::getId).reversed())
                .toList();
    }

    /**
     * 同一会话里停在「等待用户回复」的 run；多条取 id 最大的一条。
     *
     * @param workbenchSessionId 工作台会话 id
     * @return 等待回复的 run；没有为空
     */
    public Optional<WorkflowRun> findWaitingReplyRun(Long workbenchSessionId) {
        if (workbenchSessionId == null) {
            return Optional.empty();
        }
        return runs.values().stream()
                .filter(run -> workbenchSessionId.equals(run.getWorkbenchSessionId()))
                .filter(run -> "WAITING_REPLY".equals(run.getStatus()))
                .max(Comparator.comparing(WorkflowRun::getId));
    }

    /**
     * 直接写 input_json（对齐 MySQL 的单列更新）。
     *
     * @param id        主键
     * @param inputJson 新的输入 JSON
     */
    public void updateRunInput(Long id, String inputJson) {
        WorkflowRun run = runs.get(id);
        if (run != null) {
            run.setInputJson(inputJson);
        }
    }

    /**
     * 幂等查询，语义对齐 MySQL 的 {@code (app_id, client_request_id)} 唯一索引。
     *
     * @param appId           应用 id
     * @param clientRequestId 调用方幂等键
     * @return 已存在的 run；无则为空
     */
    public Optional<WorkflowRun> findRunByAppAndClientRequestId(Long appId, String clientRequestId) {
        if (appId == null || clientRequestId == null || clientRequestId.isBlank()) {
            return Optional.empty();
        }
        return runs.values().stream()
                .filter(run -> appId.equals(run.getAppId()) && clientRequestId.equals(run.getClientRequestId()))
                .findFirst();
    }

    // ---------------------------------------------------------------------
    // node_run
    // ---------------------------------------------------------------------

    public void insertNodeRuns(List<WorkflowNodeRun> rows) {
        for (WorkflowNodeRun row : rows) {
            row.setId(nodeRunSeq.incrementAndGet());
            nodeRuns.put(row.getId(), row);
        }
    }

    public List<WorkflowNodeRun> listByRunId(Long runId) {
        return nodeRuns.values().stream()
                .filter(row -> runId.equals(row.getRunId()))
                .sorted(Comparator.comparing(WorkflowNodeRun::getId))
                .toList();
    }

    /**
     * 批量取多个 run 的节点行（对话回放补全用）。
     *
     * @param runIds run 主键集合；空集合返回空表
     * @return 按主键升序的节点行
     */
    public List<WorkflowNodeRun> listByRunIds(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return nodeRuns.values().stream()
                .filter(row -> runIds.contains(row.getRunId()))
                .sorted(Comparator.comparing(WorkflowNodeRun::getId))
                .toList();
    }

    /**
     * 记录用户对询问节点的回复：同 run + nodeCode 这一行写 replyText。
     *
     * @return 受影响行数；节点不存在时为 0
     */
    public int markReply(Long runId, String nodeCode, String replyText) {
        int touched = 0;
        for (WorkflowNodeRun row : nodeRuns.values()) {
            if (!runId.equals(row.getRunId()) || nodeCode == null || !nodeCode.equals(row.getNodeCode())) {
                continue;
            }
            row.setReplyText(replyText);
            touched++;
        }
        return touched;
    }

    public Optional<WorkflowNodeRun> findNodeRun(Long id) {
        return Optional.ofNullable(nodeRuns.get(id));
    }

    public void updateNodeRun(WorkflowNodeRun nodeRun) {
        nodeRuns.put(nodeRun.getId(), nodeRun);
    }

    /**
     * 取消收口：RUNNING / WAITING_HUMAN → CANCELLED，PENDING → SKIPPED。
     */
    public int cancelOpen(Long runId) {
        int touched = 0;
        for (WorkflowNodeRun row : nodeRuns.values()) {
            if (!runId.equals(row.getRunId())) {
                continue;
            }
            if ("RUNNING".equals(row.getStatus()) || "WAITING_HUMAN".equals(row.getStatus())
                    || "WAITING_REPLY".equals(row.getStatus())) {
                row.setStatus("CANCELLED");
                row.setEndTime(LocalDateTime.now());
                touched++;
            } else if ("PENDING".equals(row.getStatus())) {
                row.setStatus("SKIPPED");
                touched++;
            }
        }
        return touched;
    }

    // ---------------------------------------------------------------------
    // human_task
    // ---------------------------------------------------------------------

    public Long insertTask(WorkflowHumanTask task) {
        long id = taskSeq.incrementAndGet();
        task.setId(id);
        task.setCreateTime(task.getCreateTime() == null ? LocalDateTime.now() : task.getCreateTime());
        humanTasks.put(id, task);
        return id;
    }

    public Optional<WorkflowHumanTask> findTask(Long id) {
        return Optional.ofNullable(humanTasks.get(id));
    }

    public List<WorkflowHumanTask> listOpenByRunId(Long runId) {
        return humanTasks.values().stream()
                .filter(task -> runId.equals(task.getRunId()) && "OPEN".equals(task.getStatus()))
                .sorted(Comparator.comparing(WorkflowHumanTask::getId))
                .toList();
    }

    public List<WorkflowHumanTask> listExpiredOpen(Long runId, LocalDateTime now) {
        List<WorkflowHumanTask> list = new ArrayList<>();
        for (WorkflowHumanTask task : humanTasks.values()) {
            if (!runId.equals(task.getRunId()) || !"OPEN".equals(task.getStatus())) {
                continue;
            }
            if (task.getExpireTime() != null && !task.getExpireTime().isAfter(now)) {
                list.add(task);
            }
        }
        return list;
    }

    /**
     * 租户待办列表，语义对齐 MySQL 实现：status 可空、按 id 倒序、limit 夹到 [1,200]。
     *
     * @param tenantId 租户
     * @param status   状态过滤，可空
     * @param limit    条数上限
     * @return 待办列表
     */
    public List<WorkflowHumanTask> listTasksByTenant(Long tenantId, String status, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        return humanTasks.values().stream()
                .filter(task -> tenantId == null || tenantId.equals(task.getTenantId()))
                .filter(task -> status == null || status.equalsIgnoreCase(task.getStatus()))
                .sorted(Comparator.comparing(WorkflowHumanTask::getId).reversed())
                .limit(size)
                .toList();
    }

    public boolean decideTask(Long id, String status, String resultJson, Long decidedBy, Long operatorId) {
        WorkflowHumanTask task = humanTasks.get(id);
        if (task == null || !"OPEN".equals(task.getStatus())) {
            return false;
        }
        task.setStatus(status);
        task.setResultJson(resultJson);
        task.setDecidedBy(decidedBy);
        task.setDecidedTime(LocalDateTime.now());
        return true;
    }

    /**
     * 直接取内存里的 run，供断言使用。
     *
     * @param id run 主键
     * @return run；不存在为 null
     */
    public WorkflowRun run(Long id) {
        return runs.get(id);
    }

    private void applyStatus(WorkflowRun run, String to, String errorSummary, String outputJson,
                             LocalDateTime endTime, Long operatorId) {
        run.setStatus(to);
        run.setErrorSummary(errorSummary);
        if (outputJson != null) {
            run.setOutputJson(outputJson);
        }
        if (endTime != null) {
            run.setEndTime(endTime);
        }
        run.setUpdatedBy(operatorId);
        run.setUpdateTime(LocalDateTime.now());
    }
}
