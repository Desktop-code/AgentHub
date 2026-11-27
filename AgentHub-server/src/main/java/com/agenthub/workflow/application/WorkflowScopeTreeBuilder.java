package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按已落库的节点行还原子图范围树。所属域：workflow 的 application。
 *
 * <p>职责：刷新回放 / 调试详情没有引擎内存时，根据父图 SUBGRAPH 节点行与钉死的
 * {@code releaseId} 重新展开每一层子图，供卡片投影铺出与直播同一套子图框。
 *
 * <p>关键约束：
 * <ul>
 *   <li>只读、旁路：解析失败只跳过该范围，不打断详情查询；</li>
 *   <li>只认已经有行的 SUBGRAPH 轮次：还没跑到的子图不提前占位；</li>
 *   <li>层级 / 环检查与运行时同一口径（3 层、同一定义出现两次即环），但只读侧跳过而非报错。</li>
 * </ul>
 */
@Component
public class WorkflowScopeTreeBuilder {

    /** 一次详情最多展开的子图范围：防御坏数据 / 极端图，不作为业务限制 */
    private static final int MAX_SCOPES = 64;

    private final WorkflowSubgraphResolver resolver;

    /**
     * @param resolver 钉死发布版本解析器
     */
    public WorkflowScopeTreeBuilder(WorkflowSubgraphResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 构建范围树。
     *
     * @param run   运行实例；取租户 / 父工作流 id
     * @param graph 运行快照的父图
     * @param rows  该 run 的全部节点行（含各层子图）
     * @return 范围树；根始终存在
     */
    public WorkflowScopeTree build(WorkflowRun run, WorkflowGraph graph, List<WorkflowNodeRun> rows) {
        WorkflowScope root = WorkflowScope.root(graph, run == null ? null : run.getWorkflowId());
        List<WorkflowScope> scopes = new ArrayList<>();
        Map<String, WorkflowScope> byNode = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<WorkflowScope, List<WorkflowNodeRun>> rowsByScope = new LinkedHashMap<>();
        scopes.add(root);
        rowsByScope.put(root, filterRootRows(root, rows));
        indexScope(root, byNode);
        expand(root, run == null ? 0L : run.getTenantId() == null ? 0L : run.getTenantId(),
                rows, scopes, byNode, labels, rowsByScope);
        return new WorkflowScopeTree(root, scopes, byNode, labels, rowsByScope);
    }

    /**
     * 递归展开：只看当前范围里「非 PENDING / 非 SKIPPED」的 SUBGRAPH 轮次。
     */
    private void expand(WorkflowScope scope, long tenantId, List<WorkflowNodeRun> allRows,
                        List<WorkflowScope> scopes, Map<String, WorkflowScope> byNode,
                        Map<String, String> labels,
                        Map<WorkflowScope, List<WorkflowNodeRun>> rowsByScope) {
        if (scope == null || scopes.size() >= MAX_SCOPES) {
            return;
        }
        // 同一 SUBGRAPH 在循环里可能跑过多轮：每一轮各是一层范围，刷新回放要都还原
        Map<String, List<WorkflowNodeRun>> attemptsByNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : allRows == null ? List.<WorkflowNodeRun>of() : allRows) {
            if (row == null || row.getNodeId() == null || !scope.owns(row.getNodeId())
                    || !ran(row.getStatus())) {
                continue;
            }
            attemptsByNode.computeIfAbsent(row.getNodeId(), ignored -> new ArrayList<>()).add(row);
        }
        for (WorkflowNode node : scope.runtime().nodes()) {
            if (scope.runtime().type(node.getId()) != WorkflowNodeType.SUBGRAPH) {
                continue;
            }
            List<WorkflowNodeRun> attempts = attemptsByNode.get(node.getId());
            if (attempts == null || attempts.isEmpty()) {
                continue;
            }
            Long subWorkflowId = WorkflowNodeConfig.longValue(node.getConfig(), "subWorkflowId");
            Long subReleaseId = WorkflowNodeConfig.longValue(node.getConfig(), "subReleaseId");
            String guard = scope.nestFailure(subWorkflowId);
            if (!guard.isEmpty()) {
                continue;
            }
            var snapshot = resolver.tryResolve(tenantId, subWorkflowId, subReleaseId);
            if (snapshot.isEmpty()) {
                continue;
            }
            attempts.sort((left, right) -> Integer.compare(attemptOf(left), attemptOf(right)));
            for (WorkflowNodeRun row : attempts) {
                int attempt = attemptOf(row);
                WorkflowScope child;
                try {
                    child = WorkflowScope.child(scope, node, attempt, snapshot.get());
                } catch (RuntimeException ex) {
                    // 坏图 / 缺 START：这一层无法展示，跳过而不是把详情查询打成 500
                    continue;
                }
                List<WorkflowNodeRun> childRows = filterRows(child, allRows);
                if (childRows.isEmpty()) {
                    continue;
                }
                scopes.add(child);
                rowsByScope.put(child, childRows);
                labels.put(node.getId(), child.label());
                indexScope(child, byNode);
                expand(child, tenantId, allRows, scopes, byNode, labels, rowsByScope);
                if (scopes.size() >= MAX_SCOPES) {
                    return;
                }
            }
        }
    }

    /** 轮次：缺省按第 1 轮。 */
    private static int attemptOf(WorkflowNodeRun row) {
        return row == null || row.getAttempt() == null || row.getAttempt() < 1 ? 1 : row.getAttempt();
    }

    /** 根范围的行：节点 id 属于根。子图行不会被误收进来。 */
    private static List<WorkflowNodeRun> filterRootRows(WorkflowScope root, List<WorkflowNodeRun> rows) {
        return filterRows(root, rows);
    }

    /** 按范围自己的节点 id 过滤行（嵌套孙图的行不会被父范围收走）。 */
    private static List<WorkflowNodeRun> filterRows(WorkflowScope scope, List<WorkflowNodeRun> rows) {
        List<WorkflowNodeRun> kept = new ArrayList<>();
        for (WorkflowNodeRun row : rows == null ? List.<WorkflowNodeRun>of() : rows) {
            if (row != null && scope.owns(row.getNodeId())) {
                kept.add(row);
            }
        }
        return List.copyOf(kept);
    }

    /** 节点 id → 范围，供投影按行找定义。 */
    private static void indexScope(WorkflowScope scope, Map<String, WorkflowScope> byNode) {
        for (WorkflowNode node : scope.runtime().nodes()) {
            if (node != null && node.getId() != null) {
                byNode.put(node.getId(), scope);
            }
        }
    }

    /** 已真的进入过该 SUBGRAPH（预建 PENDING / 未进入跳过不算）。 */
    private static boolean ran(String status) {
        WorkflowNodeRunStatus parsed = WorkflowNodeRunStatus.parse(status);
        if (parsed == null) {
            return false;
        }
        return parsed != WorkflowNodeRunStatus.PENDING && parsed != WorkflowNodeRunStatus.SKIPPED;
    }
}
