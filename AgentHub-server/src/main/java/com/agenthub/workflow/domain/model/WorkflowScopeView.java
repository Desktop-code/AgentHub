package com.agenthub.workflow.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子图执行范围在卡片投影上的只读快照。所属域：workflow 的 domain。
 *
 * <p>职责：把「父图 SUBGRAPH 节点内嵌的一层子图」需要展示的事实一次带齐——
 * 子图自己的节点、出边索引、节点运行行，以及子图节点在父图里的编码。
 * 投影层只读这份快照，不反向依赖引擎 / 调度实现。
 *
 * <p>关键约束：
 * <ul>
 *   <li>{@code nodes} 的 id / code 已加上范围前缀，避免与父图同名节点冲突；
 *       展示用的原始编码在 {@code displayCodes} 里；</li>
 *   <li>{@code parentNodeCode} 是父图上 SUBGRAPH 节点的编码，子图节点默认归到这个框下；</li>
 *   <li>只用于展示，不参与调度；不可变。</li>
 * </ul>
 *
 * @param key            范围键（父链路 + 子图节点 id#轮次），同一父运行内唯一
 * @param label          框标题：被调工作流名 + 版本（如 {@code 客服分流 · v3}）
 * @param parentNodeCode 父图上 SUBGRAPH 节点的编码（已带范围前缀）
 * @param depth          子图层级，根层为 0，第一层子图为 1
 * @param nodes          子图节点，按子图定义顺序
 * @param outgoing       子图节点 id → 出边（保持子图内顺序）
 * @param rows           子图节点的运行态行
 * @param displayCodes   子图节点 id → 原始编码（去掉范围前缀，供步骤卡附注）
 */
public record WorkflowScopeView(String key, String label, String parentNodeCode, int depth,
                                List<WorkflowNode> nodes,
                                Map<String, List<WorkflowEdge>> outgoing,
                                List<WorkflowNodeRun> rows,
                                Map<String, String> displayCodes) {

    /**
     * 防御性归一：空列表 / 空表转不可变空集合，避免投影层处理 null。
     */
    public WorkflowScopeView {
        key = key == null ? "" : key.trim();
        label = label == null ? "" : label.trim();
        parentNodeCode = parentNodeCode == null ? "" : parentNodeCode.trim();
        depth = Math.max(0, depth);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        outgoing = outgoing == null ? Map.of() : Map.copyOf(outgoing);
        rows = rows == null ? List.of() : List.copyOf(rows);
        displayCodes = displayCodes == null ? Map.of() : Map.copyOf(displayCodes);
    }

    /**
     * @param nodeId 子图节点 id（已带范围前缀）
     * @return 展示用原始编码；没有映射时为空串
     */
    public String displayCodeOf(String nodeId) {
        if (nodeId == null) {
            return "";
        }
        return displayCodes.getOrDefault(nodeId, "");
    }

    /**
     * 只取子图自己的节点行：调用方已按范围过滤时可直接用；
     * 这里再按节点 id 过滤一次，避免把嵌套孙图的行误算进来。
     *
     * @return 节点 id 在本范围内的行
     */
    public List<WorkflowNodeRun> ownRows() {
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (node != null && node.getId() != null) {
                ids.put(node.getId(), Boolean.TRUE);
            }
        }
        return rows.stream()
                .filter(row -> row != null && row.getNodeId() != null && ids.containsKey(row.getNodeId()))
                .toList();
    }
}
