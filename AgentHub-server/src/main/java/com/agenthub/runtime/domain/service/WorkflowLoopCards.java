package com.agenthub.runtime.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.service.WorkflowLoopBodies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流卡片上的「循环框」分组。所属域：runtime 的 domain（只读展示）。
 *
 * <p>为什么单独成类：工作台要把循环体里的节点圈进同一个框，框的范围只能从图上
 * {@code LOOP_BODY} / {@code LOOP_BACK} 边算出来（{@link WorkflowLoopBodies}）。
 * 这段几何关系不属于调度，也不属于卡片渲染细节，收在这里让投影只读结果。
 *
 * <p>边界：
 * <ul>
 *   <li><b>只算展示归属</b>：不改调度、不加事件、不落库；</li>
 *   <li>不传图（历史调用点没有出边索引）时两个结果都为空，节点照旧平铺；</li>
 *   <li>嵌套时取包含该节点的<b>最内层</b>循环，内层循环节点自己归属外层，
 *       因此内层框会画在外层框里面，而不是被拍平；</li>
 *   <li>线程：纯函数，只读入参。</li>
 * </ul>
 */
public final class WorkflowLoopCards {

    private WorkflowLoopCards() {
    }

    /**
     * 每个循环节点的循环体（循环节点 id → 体内节点 id）。
     *
     * @param nodes    图内节点
     * @param outgoing 节点 id → 出边；空或 null 时返回空表
     * @return 循环节点 id → 体内节点 id；没有声明循环时为空表
     */
    public static Map<String, Set<String>> bodiesOf(List<WorkflowNode> nodes,
                                                    Map<String, List<WorkflowEdge>> outgoing) {
        Map<String, Set<String>> bodies = new LinkedHashMap<>();
        if (nodes == null || outgoing == null || outgoing.isEmpty()) {
            return bodies;
        }
        for (WorkflowNode node : nodes) {
            if (node == null || node.getId() == null || !isLoop(node)) {
                continue;
            }
            Set<String> body = WorkflowLoopBodies.of(node.getId(), outgoing);
            if (!body.isEmpty()) {
                bodies.put(node.getId(), body);
            }
        }
        return bodies;
    }

    /**
     * 节点 id → 所属循环的编码。
     *
     * <p>同一节点落在多层循环体里时取最内层（体最小的那个），这样每一轮只出现在
     * 一个框里；循环节点本身不会被算进自己的体，只可能归属外层。
     *
     * @param nodes  图内节点（取编码 / 类型）
     * @param bodies 循环节点 id → 体内节点 id
     * @return 节点 id → 循环节点编码；不在任何循环体里的节点没有键
     */
    public static Map<String, String> groupByNode(List<WorkflowNode> nodes,
                                                  Map<String, Set<String>> bodies) {
        Map<String, String> groups = new LinkedHashMap<>();
        if (nodes == null || bodies == null || bodies.isEmpty()) {
            return groups;
        }
        Map<String, String> codeById = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (node != null && node.getId() != null) {
                codeById.put(node.getId(), node.getCode() == null ? "" : node.getCode().trim());
            }
        }
        // 体越小越内层：先铺内层，外层的 putIfAbsent 就不会把内层节点抢走
        List<Map.Entry<String, Set<String>>> ordered = new ArrayList<>(bodies.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> entry.getValue().size()));
        for (Map.Entry<String, Set<String>> entry : ordered) {
            String code = codeById.getOrDefault(entry.getKey(), "");
            if (code.isEmpty()) {
                continue;
            }
            for (String nodeId : new LinkedHashSet<>(entry.getValue())) {
                groups.putIfAbsent(nodeId, code);
            }
        }
        return groups;
    }

    /**
     * 是否循环节点。
     *
     * @param node 节点
     * @return true 表示类型是 LOOP
     */
    private static boolean isLoop(WorkflowNode node) {
        String type = node.getType() == null ? "" : node.getType().trim().toUpperCase();
        return WorkflowNodeType.LOOP.name().equals(type);
    }
}
