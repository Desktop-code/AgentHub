package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行期图索引。所属域：workflow 的 application。
 * <p>把校验用 {@link WorkflowGraph} 预处理成「按 id 查节点 / 按节点查出入边 / 图顺序」，
 * 调度循环每一拍直接查表，避免每次线性扫描。
 * <p>不可变：构造后只读；线程内使用，不跨请求共享。
 */
public final class WorkflowRuntimeGraph {

    private final Map<String, WorkflowNode> nodeById = new LinkedHashMap<>();
    private final Map<String, WorkflowNodeType> typeById = new LinkedHashMap<>();
    private final Map<String, List<WorkflowEdge>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<WorkflowEdge>> incoming = new LinkedHashMap<>();
    private final List<WorkflowNode> nodes;

    private WorkflowRuntimeGraph(WorkflowGraph graph) {
        this.nodes = new ArrayList<>(graph.getNodes());
        for (WorkflowNode node : nodes) {
            if (node.getId() == null || node.getId().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图里存在缺少 id 的节点，无法执行");
            }
            nodeById.put(node.getId(), node);
            WorkflowNodeType type = WorkflowNodeType.parse(node.getType());
            if (type == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "节点「" + label(node) + "」类型不合法，无法执行");
            }
            typeById.put(node.getId(), type);
            outgoing.put(node.getId(), new ArrayList<>());
            incoming.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : graph.getEdges()) {
            if (edge.getSource() == null || edge.getTarget() == null
                    || !nodeById.containsKey(edge.getSource()) || !nodeById.containsKey(edge.getTarget())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "图里存在悬空连线（" + edge.getSource() + " → " + edge.getTarget() + "），无法执行");
            }
            outgoing.get(edge.getSource()).add(edge);
            incoming.get(edge.getTarget()).add(edge);
        }
    }

    /**
     * 构造索引并做「可执行」最小检查：唯一 START、至少一个 END。
     *
     * @param graph 发布快照解析结果
     * @return 索引
     * @throws BizException 图不可执行
     */
    public static WorkflowRuntimeGraph of(WorkflowGraph graph) {
        WorkflowRuntimeGraph runtime = new WorkflowRuntimeGraph(graph);
        long starts = runtime.typeById.values().stream()
                .filter(type -> type == WorkflowNodeType.START).count();
        long ends = runtime.typeById.values().stream()
                .filter(type -> type == WorkflowNodeType.END).count();
        if (starts != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "工作流必须恰好有 1 个开始节点（当前 " + starts + " 个），无法执行");
        }
        if (ends == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工作流缺少结束节点，无法执行");
        }
        return runtime;
    }

    /**
     * @return 图定义顺序的节点列表
     */
    public List<WorkflowNode> nodes() {
        return nodes;
    }

    /**
     * @param id 节点 id
     * @return 节点；不存在为 null
     */
    public WorkflowNode node(String id) {
        return nodeById.get(id);
    }

    /**
     * @param id 节点 id
     * @return 类型；不存在为 null
     */
    public WorkflowNodeType type(String id) {
        return typeById.get(id);
    }

    /**
     * @param id 节点 id
     * @return 出边（保持图内顺序）
     */
    public List<WorkflowEdge> outgoing(String id) {
        return outgoing.getOrDefault(id, List.of());
    }

    /**
     * @param id 节点 id
     * @return 入边（保持图内顺序）
     */
    public List<WorkflowEdge> incoming(String id) {
        return incoming.getOrDefault(id, List.of());
    }

    /**
     * @return 唯一 START 节点
     */
    public WorkflowNode start() {
        return nodes.stream()
                .filter(node -> typeById.get(node.getId()) == WorkflowNodeType.START)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "工作流缺少开始节点"));
    }

    /**
     * 入边里是否有 ERROR 边；失败时决定「走错误边」还是「整 run 失败」。
     *
     * @param nodeId 节点 id
     * @return ERROR 出边列表
     */
    public List<WorkflowEdge> errorOutgoing(String nodeId) {
        return outgoing(nodeId).stream()
                .filter(edge -> WorkflowEdgeType.parse(edge.getType()) == WorkflowEdgeType.ERROR)
                .toList();
    }

    private static String label(WorkflowNode node) {
        if (node.getCode() != null && !node.getCode().isBlank()) {
            return node.getCode();
        }
        return node.getName() == null ? node.getId() : node.getName();
    }
}
