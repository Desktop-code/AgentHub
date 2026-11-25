package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.infra.WorkflowProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 循环把同一节点再跑一轮时的运行态行。所属域：workflow 的 application。
 *
 * <p>不覆盖上一轮：新行使用更大的 attempt，旧行的产出和选中边留着给回放。
 * 单次运行的 node_run 条数仍受 {@code max-node-runs-per-run} 约束，装不下就拒绝再展开。
 */
public final class WorkflowLoopAttempts {

    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowProperties properties;

    /**
     * @param nodeRuns   节点运行态仓储
     * @param properties 单次运行条数硬顶
     */
    public WorkflowLoopAttempts(WorkflowNodeRunRepository nodeRuns, WorkflowProperties properties) {
        this.nodeRuns = nodeRuns;
        this.properties = properties;
    }

    /**
     * 再插入一轮会不会超过单次运行硬顶。
     *
     * @param runId  运行 id
     * @param adding 准备新增的行数
     * @return true 表示不能再展开
     */
    public boolean exceedsCap(Long runId, int adding) {
        if (adding <= 0 || runId == null) {
            return false;
        }
        int existing = nodeRuns.listByRunId(runId).size();
        return existing + adding > properties.effectiveMaxNodeRunsPerRun();
    }

    /**
     * 为循环节点再开一轮待运行的行，供下一次进入时决定继续转还是离开。
     *
     * @param node      循环节点
     * @param latest    该节点当前最新一轮（已经成功）
     * @param rowByNode 调度用的「节点 → 最新行」，会被替换成新行
     * @return 新行；最新行还没结束时不重复插入
     */
    public WorkflowNodeRun openNext(WorkflowNode node, WorkflowNodeRun latest,
                                    Map<String, WorkflowNodeRun> rowByNode) {
        if (node == null || latest == null || !terminal(latest)) {
            return latest;
        }
        WorkflowNodeRun created = blank(node, latest.getRunId(), attemptOf(latest) + 1);
        nodeRuns.insertBatch(List.of(created));
        rowByNode.put(node.getId(), created);
        return created;
    }

    /**
     * 把循环体里已经结束的节点各开一轮新的待运行行。
     * 循环外的节点不在 ids 里，语言等已选结果不会被清掉。
     *
     * @param runtime   图
     * @param bodyIds   循环体节点 id
     * @param rowByNode 最新行索引，会被替换
     */
    public void reopen(WorkflowRuntimeGraph runtime, Iterable<String> bodyIds,
                       Map<String, WorkflowNodeRun> rowByNode) {
        List<WorkflowNodeRun> created = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String id : bodyIds) {
            WorkflowNode node = runtime.node(id);
            WorkflowNodeRun latest = rowByNode.get(id);
            if (node == null || latest == null || !terminal(latest)) {
                continue;
            }
            created.add(blank(node, latest.getRunId(), attemptOf(latest) + 1));
            ids.add(id);
        }
        if (created.isEmpty()) {
            return;
        }
        nodeRuns.insertBatch(created);
        for (int index = 0; index < created.size(); index++) {
            rowByNode.put(ids.get(index), created.get(index));
        }
    }

    private static boolean terminal(WorkflowNodeRun row) {
        WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(row.getStatus());
        return status != null && status.terminal();
    }

    private static int attemptOf(WorkflowNodeRun row) {
        Integer attempt = row.getAttempt();
        return attempt == null || attempt < 1 ? 1 : attempt;
    }

    private static WorkflowNodeRun blank(WorkflowNode node, Long runId, int attempt) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setRunId(runId);
        row.setNodeId(node.getId());
        row.setNodeCode(node.getCode());
        row.setNodeType(node.getType());
        row.setAttempt(attempt);
        row.setStatus(WorkflowNodeRunStatus.PENDING.name());
        return row;
    }
}
