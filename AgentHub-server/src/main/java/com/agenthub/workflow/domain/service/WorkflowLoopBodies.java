package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 循环体范围。所属域：workflow 的 domain。
 *
 * <p>循环体 = 从「进入循环体」边能走到、并且还能回到该循环声明回边的节点。
 * 循环节点自己、超限出口后面的下游，都不算在体内。嵌套时内层节点同时属于外层循环体。
 */
public final class WorkflowLoopBodies {

    private WorkflowLoopBodies() {
    }

    /**
     * 某个循环节点的循环体（不含它自己）。
     *
     * @param loopId   循环节点 id
     * @param outgoing 节点 id → 出边
     * @return 循环体节点 id；入口或回边缺失时为空集
     */
    public static Set<String> of(String loopId, Map<String, List<WorkflowEdge>> outgoing) {
        if (loopId == null || outgoing == null) {
            return Set.of();
        }
        List<String> entries = new ArrayList<>();
        for (WorkflowEdge edge : outgoing.getOrDefault(loopId, List.of())) {
            if (WorkflowEdgeType.parse(edge.getType()) != WorkflowEdgeType.LOOP_BODY) {
                continue;
            }
            if (edge.getTarget() != null && !edge.getTarget().equals(loopId)) {
                entries.add(edge.getTarget());
            }
        }
        Set<String> backSources = new LinkedHashSet<>();
        for (Map.Entry<String, List<WorkflowEdge>> entry : outgoing.entrySet()) {
            for (WorkflowEdge edge : entry.getValue()) {
                if (WorkflowEdgeType.parse(edge.getType()) == WorkflowEdgeType.LOOP_BACK
                        && loopId.equals(edge.getTarget())) {
                    backSources.add(entry.getKey());
                }
            }
        }
        if (entries.isEmpty() || backSources.isEmpty()) {
            return Set.of();
        }
        // 正向能从入口走到，反向又能回到回边源，两边都在的才是这一圈的循环体
        Set<String> forward = walk(entries, adjacency(outgoing, false), loopId);
        Set<String> backward = walk(backSources, adjacency(outgoing, true), loopId);
        forward.retainAll(backward);
        forward.remove(loopId);
        return forward;
    }

    /**
     * 节点落在哪些循环的体内。
     *
     * @param nodeId   节点 id
     * @param loopIds  图中循环节点 id（调用方保证稳定顺序）
     * @param outgoing 出边索引
     * @return 包含该节点的循环 id
     */
    public static List<String> containers(String nodeId, List<String> loopIds,
                                          Map<String, List<WorkflowEdge>> outgoing) {
        List<String> found = new ArrayList<>();
        if (nodeId == null || loopIds == null) {
            return found;
        }
        for (String loopId : loopIds) {
            if (of(loopId, outgoing).contains(nodeId)) {
                found.add(loopId);
            }
        }
        return found;
    }

    /**
     * 出边转成节点 id 邻接。反向时用于「谁能回到回边源」。
     *
     * @param outgoing 原始出边
     * @param reverse  true 表示沿入边往回走
     * @return 节点 id → 相邻节点 id
     */
    private static Map<String, List<String>> adjacency(Map<String, List<WorkflowEdge>> outgoing,
                                                       boolean reverse) {
        Map<String, List<String>> adjacent = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorkflowEdge>> entry : outgoing.entrySet()) {
            for (WorkflowEdge edge : entry.getValue()) {
                if (edge.getTarget() == null) {
                    continue;
                }
                String from = reverse ? edge.getTarget() : entry.getKey();
                String to = reverse ? entry.getKey() : edge.getTarget();
                adjacent.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
            }
        }
        return adjacent;
    }

    /**
     * 广度优先。遇到当前循环节点就停，避免顺着回边或入口再冲到循环外面。
     *
     * @param roots    起点（计入结果）
     * @param adjacent 邻接表
     * @param stopAt   不再穿越的节点，通常是循环节点自己
     * @return 到达过的节点
     */
    private static Set<String> walk(Collection<String> roots, Map<String, List<String>> adjacent,
                                    String stopAt) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String root : roots) {
            if (root != null && !root.equals(stopAt) && seen.add(root)) {
                queue.add(root);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacent.getOrDefault(current, List.of())) {
                if (next == null || next.equals(stopAt) || !seen.add(next)) {
                    continue;
                }
                queue.add(next);
            }
        }
        return seen;
    }
}
