package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowScopeView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一层子图的执行范围。所属域：workflow 的 application。
 *
 * <p>职责：把发布快照里的图转换成与父图隔离的运行时图，并带上调用栈 / 层级 / 范围前缀。
 * 父图与子图可以有同名节点；范围前缀保证运行行、卡片视图键与循环框编码都不互相覆盖。
 *
 * <p>关键约束：
 * <ul>
 *   <li>只由引擎与只读投影构造；不写库、不改发布快照；</li>
 *   <li>调用栈上同一工作流定义出现两次视为环；SUBGRAPH 层级硬顶为 3；</li>
 *   <li>{@link #cleanText(String)} 去掉本范围及祖先范围的前缀，错误文案里不暴露内部编码。</li>
 * </ul>
 */
final class WorkflowScope {

    /** SUBGRAPH 嵌套层级硬顶：只计子图层，子图内部普通节点与循环不算一层 */
    static final int MAX_DEPTH = 3;

    private final String key;
    private final String prefix;
    private final int depth;
    private final Long workflowId;
    private final WorkflowSubgraphSnapshot snapshot;
    private final List<Long> workflowStack;
    private final WorkflowGraph graph;
    private final WorkflowRuntimeGraph runtime;
    private final Map<String, String> displayCodes;
    private final List<String> prefixes;
    private final String parentNodeId;
    private final String parentNodeCode;
    private final Integer parentAttempt;

    private WorkflowScope(String key, String prefix, int depth, Long workflowId,
                          WorkflowSubgraphSnapshot snapshot, List<Long> workflowStack,
                          WorkflowGraph graph, WorkflowRuntimeGraph runtime,
                          Map<String, String> displayCodes, List<String> prefixes,
                          String parentNodeId, String parentNodeCode, Integer parentAttempt) {
        this.key = key;
        this.prefix = prefix;
        this.depth = depth;
        this.workflowId = workflowId;
        this.snapshot = snapshot;
        this.workflowStack = workflowStack;
        this.graph = graph;
        this.runtime = runtime;
        this.displayCodes = displayCodes;
        this.prefixes = prefixes;
        this.parentNodeId = parentNodeId;
        this.parentNodeCode = parentNodeCode;
        this.parentAttempt = parentAttempt;
    }

    /**
     * 根范围：父图自己的节点，不加前缀。
     *
     * @param graph      父图（run 上落库的发布 / 草稿快照）
     * @param workflowId 父工作流定义主键；可空
     * @return 根范围
     */
    static WorkflowScope root(WorkflowGraph graph, Long workflowId) {
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        Map<String, String> codes = new LinkedHashMap<>();
        for (WorkflowNode node : runtime.nodes()) {
            if (node != null && node.getId() != null) {
                codes.put(node.getId(), node.getCode() == null ? "" : node.getCode());
            }
        }
        List<Long> stack = new ArrayList<>();
        if (workflowId != null) {
            stack.add(workflowId);
        }
        return new WorkflowScope("root", "", 0, workflowId, null, List.copyOf(stack),
                graph, runtime, codes, List.of(), null, null, null);
    }

    /**
     * 建一层子图范围。
     *
     * <p>调用方必须先通过 {@link #nestFailure(Long)} 检查环与层级。
     *
     * @param parent     父范围
     * @param parentNode 父图上的 SUBGRAPH 节点（已带父范围前缀）
     * @param attempt    该 SUBGRAPH 节点的轮次；至少为 1
     * @param snapshot   钉死的发布快照
     * @return 子范围
     * @throws com.agenthub.common.error.BizException 发布快照的图不可执行
     */
    static WorkflowScope child(WorkflowScope parent, WorkflowNode parentNode, int attempt,
                               WorkflowSubgraphSnapshot snapshot) {
        int round = Math.max(1, attempt);
        String parentId = parentNode == null ? "" : parentNode.getId();
        String key = parent.key + "/" + parentId + "#" + round;
        String prefix = WorkflowScopeGraphs.prefixOf(key);
        WorkflowGraph graph = WorkflowScopeGraphs.transform(snapshot.graph(), prefix);
        WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
        Map<String, String> codes = WorkflowScopeGraphs.displayCodes(snapshot.graph(), prefix);
        List<String> prefixes = new ArrayList<>(parent.prefixes);
        prefixes.add(prefix);
        List<Long> stack = new ArrayList<>(parent.workflowStack);
        stack.add(snapshot.workflowId());
        return new WorkflowScope(key, prefix, parent.depth + 1, snapshot.workflowId(), snapshot,
                List.copyOf(stack), graph, runtime, codes, List.copyOf(prefixes),
                parentId, parentNode == null ? "" : parentNode.getCode(), round);
    }

    /**
     * 检查能否再嵌一层子图；能嵌返回空串，否则返回面向用户的中文原因。
     *
     * @param subWorkflowId 被调工作流定义主键
     * @return 空串表示可以继续；非空为失败原因
     */
    String nestFailure(Long subWorkflowId) {
        if (subWorkflowId == null) {
            return "子图节点未绑定被调工作流，请选择后再运行";
        }
        if (workflowStack.contains(subWorkflowId)) {
            return "检测到工作流循环调用（同一工作流已在调用链中），已终止";
        }
        if (depth + 1 > MAX_DEPTH) {
            return "子图嵌套层级超过上限（最多支持 " + MAX_DEPTH + " 层子图）";
        }
        return "";
    }

    /**
     * 子图节点的原始编码：运行行 / 视图键用前缀后的值，{@code $.outputs} 与展示用原始值。
     *
     * @param node 本范围内的节点
     * @return 原始编码；节点为空时为空串
     */
    String outputCode(WorkflowNode node) {
        if (node == null) {
            return "";
        }
        String code = displayCodes.get(node.getId());
        return code == null || code.isBlank() ? (node.getCode() == null ? "" : node.getCode()) : code;
    }

    /**
     * @param nodeId 本范围内的节点 id（带前缀）
     * @return 原始编码；没有映射时回落到节点自己的 code
     */
    String displayCodeOf(String nodeId) {
        return displayCodes.getOrDefault(nodeId, "");
    }

    /**
     * 展示名：优先节点自定义名称，其次原始编码；用于错误文案与框内节点标题。
     *
     * @param node 本范围内的节点
     * @return 面向用户的名称
     */
    String displayName(WorkflowNode node) {
        if (node == null) {
            return "";
        }
        String name = node.getName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String code = outputCode(node);
        return code.isBlank() ? "未命名节点" : code;
    }

    /**
     * 去掉本范围及所有祖先范围的前缀，供错误文案 / 节点产出里的内部编码还原。
     *
     * @param text 原始文本；可空
     * @return 清理后的文本
     */
    String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String cleaned = text;
        for (int index = prefixes.size() - 1; index >= 0; index -= 1) {
            cleaned = cleaned.replace(prefixes.get(index), "");
        }
        return cleaned;
    }

    /**
     * 本范围的卡片投影快照。
     *
     * @param rows 本范围自己的节点行
     * @return 只读快照
     */
    WorkflowScopeView view(List<WorkflowNodeRun> rows) {
        return new WorkflowScopeView(key, label(), parentNodeCode, depth, runtime.nodes(),
                runtime.outgoingIndex(), rows, displayCodes);
    }

    /**
     * @return 框标题：被调工作流名 + 版本
     */
    String label() {
        return snapshot == null ? "" : snapshot.label();
    }

    String key() {
        return key;
    }

    String prefix() {
        return prefix;
    }

    int depth() {
        return depth;
    }

    Long workflowId() {
        return workflowId;
    }

    WorkflowSubgraphSnapshot snapshot() {
        return snapshot;
    }

    List<Long> workflowStack() {
        return workflowStack;
    }

    WorkflowGraph graph() {
        return graph;
    }

    WorkflowRuntimeGraph runtime() {
        return runtime;
    }

    String parentNodeId() {
        return parentNodeId;
    }

    String parentNodeCode() {
        return parentNodeCode;
    }

    Integer parentAttempt() {
        return parentAttempt;
    }

    /**
     * @return 本范围的节点行 key（nodeId#attempt）
     */
    Map<String, WorkflowNodeRun> indexRows(List<WorkflowNodeRun> rows) {
        Map<String, WorkflowNodeRun> byNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : rows == null ? List.<WorkflowNodeRun>of() : rows) {
            if (row == null || row.getNodeId() == null || !displayCodes.containsKey(row.getNodeId())) {
                continue;
            }
            // 同一节点多轮：调度只看最新一轮；历史行仍保留在库里供回放
            byNode.put(row.getNodeId(), row);
        }
        return byNode;
    }

    /**
     * @param nodeId 本范围内的节点 id
     * @return 是否属于本范围
     */
    boolean owns(String nodeId) {
        return nodeId != null && displayCodes.containsKey(nodeId);
    }

    /**
     * @return 本范围的出边索引（只读）
     */
    Map<String, List<WorkflowEdge>> outgoingIndex() {
        return runtime.outgoingIndex();
    }
}
