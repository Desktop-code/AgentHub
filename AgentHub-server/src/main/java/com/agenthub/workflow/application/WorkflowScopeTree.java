package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowScopeView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行里已经发生的子图范围树（只读）。所属域：workflow 的 application。
 *
 * <p>职责：按已落库的节点行还原「父图 SUBGRAPH 节点 → 子图范围 → 孙图范围」，
 * 供刷新回放 / 调试详情一次性铺出同一张卡里的所有子图框。
 *
 * <p>关键约束：
 * <ul>
 *   <li>只读：不改行、不写库、不触发调度；</li>
 *   <li>只展开「已经有子图节点行」的范围，没跑到的子图不提前占位；</li>
 *   <li>复用与引擎同一套解析器：版本缺失 / 跨租户的范围直接跳过，不把卡片打成 500。</li>
 * </ul>
 */
public final class WorkflowScopeTree {

    private final WorkflowScope root;
    private final List<WorkflowScope> scopes;
    private final Map<String, WorkflowScope> scopeByNodeId;
    private final Map<String, String> labels;
    private final Map<WorkflowScope, List<WorkflowNodeRun>> rowsByScope;

    WorkflowScopeTree(WorkflowScope root, List<WorkflowScope> scopes,
                      Map<String, WorkflowScope> scopeByNodeId, Map<String, String> labels,
                      Map<WorkflowScope, List<WorkflowNodeRun>> rowsByScope) {
        this.root = root;
        this.scopes = List.copyOf(scopes);
        this.scopeByNodeId = Map.copyOf(scopeByNodeId);
        this.labels = Map.copyOf(labels);
        this.rowsByScope = Map.copyOf(rowsByScope);
    }

    /**
     * @return 根范围的节点定义，已把 SUBGRAPH 节点名换成「被调工作流名 · vN」
     */
    public List<WorkflowNode> rootNodes() {
        return labeledNodes(root);
    }

    /**
     * @return 根范围出边索引（只读）
     */
    public Map<String, List<WorkflowEdge>> rootOutgoing() {
        return root.outgoingIndex();
    }

    /**
     * 子图范围快照，按父 → 子的先序排列；投影按这个顺序补节点。
     *
     * @return 子图范围（不含根）
     */
    public List<WorkflowScopeView> childViews() {
        List<WorkflowScopeView> views = new ArrayList<>();
        for (WorkflowScope scope : scopes) {
            if (scope == root) {
                continue;
            }
            WorkflowScopeView raw = scope.view(rowsByScope.getOrDefault(scope, List.of()));
            /*
             * 子图内部的嵌套 SUBGRAPH 节点也要显示「被调工作流名 · vN」：
             * 框标题取父节点展示名，回放与直播必须一致，否则刷新后框只剩内部编码。
             */
            List<WorkflowNode> labeled = new ArrayList<>();
            for (WorkflowNode node : raw.nodes()) {
                String label = node == null ? null : labels.get(node.getId());
                labeled.add(label == null || label.isBlank() ? node : WorkflowScopeGraphs.withName(node, label));
            }
            views.add(new WorkflowScopeView(raw.key(), raw.label(), raw.parentNodeCode(), raw.depth(),
                    labeled, raw.outgoing(), raw.rows(), raw.displayCodes()));
        }
        return List.copyOf(views);
    }

    /**
     * 按节点 id 找它所属的范围；根节点也返回根范围。
     *
     * @param nodeId 节点 id（带范围前缀）
     * @return 范围；找不到为空
     */
    public WorkflowScope scopeOfNode(String nodeId) {
        return nodeId == null ? null : scopeByNodeId.get(nodeId);
    }

    /**
     * 取某节点的定义（含展示名标签）。
     *
     * @param nodeId 节点 id
     * @return 节点；找不到为空
     */
    public WorkflowNode nodeOf(String nodeId) {
        WorkflowScope scope = scopeOfNode(nodeId);
        return scope == null || nodeId == null ? null : scope.runtime().node(nodeId);
    }

    /**
     * 取某节点的原始展示编码（去掉范围前缀）。
     *
     * @param nodeId 节点 id
     * @return 原始编码；找不到为空串
     */
    public String displayCodeOf(String nodeId) {
        WorkflowScope scope = scopeOfNode(nodeId);
        return scope == null ? "" : scope.displayCodeOf(nodeId);
    }

    /**
     * @param scope 范围
     * @return 该范围的节点行（已按该范围过滤）
     */
    public List<WorkflowNodeRun> rowsOf(WorkflowScope scope) {
        return rowsByScope.getOrDefault(scope, List.of());
    }

    /**
     * 取该范围里带标签的节点：SUBGRAPH 节点名换成「被调工作流名 · vN」。
     *
     * @param scope 范围
     * @return 节点列表
     */
    List<WorkflowNode> labeledNodes(WorkflowScope scope) {
        if (scope == null) {
            return List.of();
        }
        List<WorkflowNode> nodes = new ArrayList<>();
        for (WorkflowNode node : scope.runtime().nodes()) {
            String label = node == null ? null : labels.get(node.getId());
            nodes.add(label == null || label.isBlank() ? node : WorkflowScopeGraphs.withName(node, label));
        }
        return List.copyOf(nodes);
    }

    /**
     * 为父图节点找它自己的范围（根节点返回根）。
     *
     * @param nodeId 节点 id
     * @return 范围；找不到为空
     */
    WorkflowScope root() {
        return root;
    }

    /**
     * @return 所有范围（根在最前）
     */
    List<WorkflowScope> scopes() {
        return scopes;
    }
}
