package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.HumanTimeoutPolicy;
import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowAgentBinding;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowIssue;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowToolBinding;
import com.agenthub.workflow.domain.model.WorkflowValidationResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作流图校验器。所属域：workflow 的 domain。
 * <p>纯函数：不依赖 Spring / 仓储 / 时钟，输入图与绑定快照，输出 errors / warns。
 * 同一个校验器同时服务「校验 API」与「发布前置检查」；publishMode 只影响
 * 「绑定目标不存在或停用」的级别（发布 error / 草稿 warn），其余规则始终 error。
 * <p>本刀不做条件表达式求值：CONDITION 只做 JSONLogic 字段的结构存盘，
 * 求值失败与否留给引擎刀。
 */
public final class WorkflowGraphValidator {

    /** 单图节点默认硬顶；生效值可由 {@code agenthub.workflow.max-nodes-per-graph} 覆盖 */
    static final int MAX_NODES = 500;

    /** 单图边硬顶 */
    static final int MAX_EDGES = 2000;

    /** 允许的上下文映射路径前缀（与定稿 §3.4 对齐） */
    private static final Pattern MAPPING_PATH =
            Pattern.compile("^\\$\\.(input|outputs|vars)(\\.|\\[|$)");

    private WorkflowGraphValidator() {
    }

    /**
     * 执行图校验（默认节点硬顶 {@link #MAX_NODES}）。
     *
     * @param graph       图；null 按空图处理
     * @param bindings    绑定快照；null 按全部不存在处理
     * @param publishMode true=发布前置检查（绑定缺失/停用为 error）；false=草稿检查（降为 warn）
     * @return errors / warns；errors 为空才允许发布
     */
    public static WorkflowValidationResult validate(WorkflowGraph graph,
                                                    WorkflowBindingSnapshot bindings,
                                                    boolean publishMode) {
        return validate(graph, bindings, publishMode, MAX_NODES);
    }

    /**
     * 执行图校验（可覆盖节点硬顶）。
     *
     * @param graph          图；null 按空图处理
     * @param bindings       绑定快照；null 按全部不存在处理
     * @param publishMode    true=发布前置检查；false=草稿检查
     * @param maxNodesPerGraph 生效的节点上限；<=0 时回落到 {@link #MAX_NODES}
     * @return errors / warns；errors 为空才允许发布
     */
    public static WorkflowValidationResult validate(WorkflowGraph graph,
                                                    WorkflowBindingSnapshot bindings,
                                                    boolean publishMode,
                                                    int maxNodesPerGraph) {
        WorkflowBindingSnapshot safeBindings =
                bindings == null ? WorkflowBindingSnapshot.empty() : bindings;
        WorkflowValidationResult result = new WorkflowValidationResult();
        List<WorkflowNode> nodes = graph == null || graph.getNodes() == null
                ? List.of() : graph.getNodes();
        List<WorkflowEdge> edges = graph == null || graph.getEdges() == null
                ? List.of() : graph.getEdges();

        // 超硬顶先报，避免后续 O(n) 检查把超大脏图拖死
        int nodeLimit = maxNodesPerGraph <= 0 ? MAX_NODES : maxNodesPerGraph;
        if (nodes.size() > nodeLimit) {
            addError(result, "GRAPH_TOO_LARGE",
                    "节点数 " + nodes.size() + " 超过上限 " + nodeLimit
                            + "（可由 agenthub.workflow.max-nodes-per-graph 调整）", null);
        }
        if (edges.size() > MAX_EDGES) {
            addError(result, "GRAPH_TOO_LARGE",
                    "连线数 " + edges.size() + " 超过上限 " + MAX_EDGES, null);
        }

        Map<String, WorkflowNode> nodeById = new LinkedHashMap<>();
        Map<String, WorkflowNodeType> nodeTypes = new HashMap<>();
        Set<String> nodeCodes = new HashSet<>();
        List<WorkflowNode> startNodes = new ArrayList<>();
        List<WorkflowNode> endNodes = new ArrayList<>();
        checkNodes(nodes, result, nodeById, nodeTypes, nodeCodes, startNodes, endNodes);

        // 入口/出口是图的根约束：空图也必须明确报错，不能静默通过
        if (startNodes.isEmpty()) {
            addError(result, "START_MISSING", "图必须恰好有 1 个 START 节点，当前没有", null);
        } else if (startNodes.size() > 1) {
            addError(result, "START_MULTIPLE",
                    "图只能有 1 个 START 节点，当前有 " + startNodes.size() + " 个", startNodes.get(1));
        }
        if (endNodes.isEmpty()) {
            addError(result, "END_MISSING", "图至少需要 1 个 END 节点，当前没有", null);
        }

        Map<String, List<WorkflowEdge>> outgoing = new HashMap<>();
        Map<String, List<WorkflowEdge>> incoming = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        checkEdges(edges, result, nodeById, edgeIds, outgoing, incoming);

        checkCycle(result, nodeById, outgoing);
        checkConditions(result, nodeById, nodeTypes, outgoing);
        checkForkJoin(result, nodeById, nodeTypes);
        checkHuman(result, nodeById, nodeTypes);
        checkBindings(result, nodeById, nodeTypes, safeBindings, publishMode);
        checkWarns(result, nodeById, nodeTypes, startNodes, endNodes, outgoing, incoming);
        return result;
    }

    /**
     * 节点级检查：id/code 唯一、类型合法且属于 P0、统计 START/END。
     */
    private static void checkNodes(List<WorkflowNode> nodes,
                                   WorkflowValidationResult result,
                                   Map<String, WorkflowNode> nodeById,
                                   Map<String, WorkflowNodeType> nodeTypes,
                                   Set<String> nodeCodes,
                                   List<WorkflowNode> startNodes,
                                   List<WorkflowNode> endNodes) {
        for (WorkflowNode node : nodes) {
            if (node == null) {
                addError(result, "NODE_NULL", "节点列表里存在 null 元素", null);
                continue;
            }
            String nodeId = trimToNull(node.getId());
            if (nodeId == null) {
                addError(result, "NODE_ID_BLANK", "节点 id 不能为空", node);
            } else if (nodeById.putIfAbsent(nodeId, node) != null) {
                addError(result, "NODE_ID_DUPLICATE", "节点 id「" + nodeId + "」重复", node);
            }

            String code = trimToNull(node.getCode());
            if (code == null) {
                addError(result, "NODE_CODE_BLANK", "节点 code 不能为空", node);
            } else if (!nodeCodes.add(code)) {
                addError(result, "NODE_CODE_DUPLICATE", "节点 code「" + code + "」在图内重复", node);
            }

            WorkflowNodeType type = WorkflowNodeType.parse(node.getType());
            if (type == null) {
                addError(result, "NODE_TYPE_UNKNOWN",
                        "节点「" + label(node) + "」类型为空或未知：" + node.getType(), node);
                continue;
            }
            nodeTypes.put(nodeId, type);
            if (!type.p0Supported()) {
                addError(result, "NODE_TYPE_P1_ONLY",
                        "节点「" + label(node) + "」类型 " + type + " 属 P1，本刀只支持 P0 节点",
                        node);
            }
            if (type == WorkflowNodeType.START) {
                startNodes.add(node);
            } else if (type == WorkflowNodeType.END) {
                endNodes.add(node);
            }
        }
    }

    /**
     * 边级检查：id 唯一、类型合法、端点存在；通过的边进入出入度索引。
     */
    private static void checkEdges(List<WorkflowEdge> edges,
                                   WorkflowValidationResult result,
                                   Map<String, WorkflowNode> nodeById,
                                   Set<String> edgeIds,
                                   Map<String, List<WorkflowEdge>> outgoing,
                                   Map<String, List<WorkflowEdge>> incoming) {
        for (WorkflowEdge edge : edges) {
            if (edge == null) {
                addError(result, "EDGE_NULL", "连线列表里存在 null 元素", null);
                continue;
            }
            String edgeId = trimToNull(edge.getId());
            if (edgeId == null) {
                addError(result, "EDGE_ID_BLANK", "连线 id 不能为空", null);
            } else if (!edgeIds.add(edgeId)) {
                addError(result, "EDGE_ID_DUPLICATE", "连线 id「" + edgeId + "」重复", null);
            }
            if (WorkflowEdgeType.parse(edge.getType()) == null) {
                addError(result, "EDGE_TYPE_UNKNOWN",
                        "连线「" + edgeLabel(edge) + "」类型为空或未知：" + edge.getType(), null);
            }
            String source = trimToNull(edge.getSource());
            String target = trimToNull(edge.getTarget());
            boolean sourceOk = source != null && nodeById.containsKey(source);
            boolean targetOk = target != null && nodeById.containsKey(target);
            if (!sourceOk || !targetOk) {
                addError(result, "EDGE_DANGLING",
                        "连线「" + edgeLabel(edge) + "」端点不存在（source=" + source
                                + "，target=" + target + "）", null);
                continue;
            }
            outgoing.computeIfAbsent(source, key -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(target, key -> new ArrayList<>()).add(edge);
        }
    }

    /**
     * 有向无环检测（DFS 三色）。发现环时把环上节点串进中文提示。
     */
    private static void checkCycle(WorkflowValidationResult result,
                                   Map<String, WorkflowNode> nodeById,
                                   Map<String, List<WorkflowEdge>> outgoing) {
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String nodeId : nodeById.keySet()) {
            List<String> cycle = dfsCycle(nodeId, outgoing, state, path);
            if (cycle != null) {
                WorkflowNode first = nodeById.get(cycle.get(0));
                addError(result, "CYCLE",
                        "图存在有向环：" + String.join(" → ", cycle) + "；P0 禁止回边", first);
                return;
            }
        }
    }

    /**
     * DFS 三色递归：0 未访问、1 正在栈上、2 已完成。
     *
     * @return 环路径（首尾同节点）；无环返回 null
     */
    private static List<String> dfsCycle(String nodeId,
                                         Map<String, List<WorkflowEdge>> outgoing,
                                         Map<String, Integer> state,
                                         Deque<String> path) {
        int current = state.getOrDefault(nodeId, 0);
        if (current == 1) {
            // 正在栈上的节点被再次访问：从路径中截出环
            List<String> cycle = new ArrayList<>();
            boolean collect = false;
            for (String onPath : path) {
                if (onPath.equals(nodeId)) {
                    collect = true;
                }
                if (collect) {
                    cycle.add(onPath);
                }
            }
            cycle.add(nodeId);
            return cycle;
        }
        if (current == 2) {
            return null;
        }
        state.put(nodeId, 1);
        path.addLast(nodeId);
        for (WorkflowEdge edge : outgoing.getOrDefault(nodeId, List.of())) {
            List<String> cycle = dfsCycle(edge.getTarget(), outgoing, state, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.removeLast();
        state.put(nodeId, 2);
        return null;
    }

    /**
     * CONDITION 出口约束：有且仅有 1 条 DEFAULT；没有 COND 边给 warn。
     */
    private static void checkConditions(WorkflowValidationResult result,
                                        Map<String, WorkflowNode> nodeById,
                                        Map<String, WorkflowNodeType> nodeTypes,
                                        Map<String, List<WorkflowEdge>> outgoing) {
        for (WorkflowNode node : nodeById.values()) {
            if (nodeTypes.get(node.getId()) != WorkflowNodeType.CONDITION) {
                continue;
            }
            List<WorkflowEdge> out = outgoing.getOrDefault(node.getId(), List.of());
            long defaults = out.stream()
                    .filter(edge -> WorkflowEdgeType.parse(edge.getType()) == WorkflowEdgeType.DEFAULT)
                    .count();
            long conditions = out.stream()
                    .filter(edge -> WorkflowEdgeType.parse(edge.getType()) == WorkflowEdgeType.COND)
                    .count();
            if (defaults == 0) {
                addError(result, "CONDITION_DEFAULT_MISSING",
                        "条件节点「" + label(node) + "」缺少 DEFAULT 出口；所有条件都不成立时无兜底",
                        node);
            } else if (defaults > 1) {
                addError(result, "CONDITION_DEFAULT_MULTIPLE",
                        "条件节点「" + label(node) + "」有 " + defaults + " 条 DEFAULT 出口，只允许 1 条",
                        node);
            }
            if (conditions == 0) {
                addWarn(result, "CONDITION_COND_MISSING",
                        "条件节点「" + label(node) + "」还没有 COND 条件边，只靠 DEFAULT 会恒走兜底",
                        node);
            }
        }
    }

    /**
     * 并行分叉/汇合成对：同一 forkGroupId 必须同时存在 FORK 与 JOIN。
     */
    private static void checkForkJoin(WorkflowValidationResult result,
                                      Map<String, WorkflowNode> nodeById,
                                      Map<String, WorkflowNodeType> nodeTypes) {
        Map<String, List<WorkflowNode>> forks = new LinkedHashMap<>();
        Map<String, List<WorkflowNode>> joins = new LinkedHashMap<>();
        for (WorkflowNode node : nodeById.values()) {
            WorkflowNodeType type = nodeTypes.get(node.getId());
            if (type != WorkflowNodeType.PARALLEL_FORK && type != WorkflowNodeType.PARALLEL_JOIN) {
                continue;
            }
            String group = WorkflowNodeConfig.textValue(node.getConfig(), "forkGroupId");
            if (group == null) {
                addError(result, "FORK_GROUP_MISSING",
                        (type == WorkflowNodeType.PARALLEL_FORK ? "并行分叉" : "并行汇合")
                                + "节点「" + label(node) + "」未配置 forkGroupId，无法成对追踪",
                        node);
                continue;
            }
            Map<String, List<WorkflowNode>> target =
                    type == WorkflowNodeType.PARALLEL_FORK ? forks : joins;
            target.computeIfAbsent(group, key -> new ArrayList<>()).add(node);
        }
        for (Map.Entry<String, List<WorkflowNode>> entry : forks.entrySet()) {
            if (!joins.containsKey(entry.getKey())) {
                addError(result, "FORK_WITHOUT_JOIN",
                        "并行分叉组「" + entry.getKey() + "」没有对应的 PARALLEL_JOIN；禁止只分不合",
                        entry.getValue().get(0));
            }
            if (entry.getValue().size() > 1) {
                addWarn(result, "FORK_GROUP_MULTIPLE",
                        "并行分叉组「" + entry.getKey() + "」有 " + entry.getValue().size()
                                + " 个 FORK，请确认是否应复用同一汇合点",
                        entry.getValue().get(0));
            }
        }
        for (Map.Entry<String, List<WorkflowNode>> entry : joins.entrySet()) {
            if (!forks.containsKey(entry.getKey())) {
                addError(result, "JOIN_WITHOUT_FORK",
                        "并行汇合组「" + entry.getKey() + "」没有对应的 PARALLEL_FORK",
                        entry.getValue().get(0));
            }
        }
    }

    /**
     * HUMAN 节点：超时策略必填；处理人规则（W4）非法拒发、空规则只提醒。
     *
     * <p>空规则仍可发布（刀2 存量图兼容）：语义是「任何具备 workflow:human:act
     * 且同租户的用户都能处理」，UI 只做建议提示，不阻断发布。
     */
    private static void checkHuman(WorkflowValidationResult result,
                                   Map<String, WorkflowNode> nodeById,
                                   Map<String, WorkflowNodeType> nodeTypes) {
        for (WorkflowNode node : nodeById.values()) {
            if (nodeTypes.get(node.getId()) != WorkflowNodeType.HUMAN) {
                continue;
            }
            String raw = WorkflowNodeConfig.textValue(node.getConfig(), "timeoutPolicy");
            if (raw == null) {
                addError(result, "HUMAN_TIMEOUT_MISSING",
                        "人工节点「" + label(node)
                                + "」必须配置超时策略（AUTO_REJECT / AUTO_APPROVE / ESCALATE）",
                        node);
            } else if (HumanTimeoutPolicy.parse(raw) == null) {
                addError(result, "HUMAN_TIMEOUT_INVALID",
                        "人工节点「" + label(node) + "」超时策略只能是 "
                                + "AUTO_REJECT / AUTO_APPROVE / ESCALATE，当前为 " + raw,
                        node);
            }
            checkAssignee(result, node);
        }
    }

    /**
     * 处理人规则校验：格式非法直接 error（避免发布出「谁都批不了」或「规则被静默忽略」的图），
     * 空规则给 warn。
     *
     * @param result 校验结果
     * @param node   HUMAN 节点
     */
    private static void checkAssignee(WorkflowValidationResult result, WorkflowNode node) {
        String raw = WorkflowNodeConfig.textValue(node.getConfig(), "assigneeRule");
        if (raw == null) {
            addWarn(result, "HUMAN_ASSIGNEE_EMPTY",
                    "人工节点「" + label(node) + "」未配置处理人规则：任何具备 "
                            + "workflow:human:act 的用户都能处理；建议配置 ROLE:角色码 或 USER:用户id",
                    node);
            return;
        }
        WorkflowAssigneeRule.Parsed parsed = WorkflowAssigneeRule.parse(raw);
        if (parsed.problem() != null) {
            addError(result, "HUMAN_ASSIGNEE_INVALID",
                    "人工节点「" + label(node) + "」处理人规则不合法：" + parsed.problem(), node);
        }
    }

    /**
     * 绑定校验：AGENT 禁 ROUTER（始终 error）；AGENT/TOOL 不存在或停用按 publishMode 定级。
     */
    private static void checkBindings(WorkflowValidationResult result,
                                      Map<String, WorkflowNode> nodeById,
                                      Map<String, WorkflowNodeType> nodeTypes,
                                      WorkflowBindingSnapshot bindings,
                                      boolean publishMode) {
        for (WorkflowNode node : nodeById.values()) {
            WorkflowNodeType type = nodeTypes.get(node.getId());
            if (type == WorkflowNodeType.AGENT) {
                checkAgent(result, node, bindings, publishMode);
            } else if (type == WorkflowNodeType.TOOL) {
                checkTool(result, node, bindings, publishMode);
            }
        }
    }

    /**
     * AGENT 绑定：先查 ROUTER 硬禁，再查存在/启用。
     */
    private static void checkAgent(WorkflowValidationResult result,
                                   WorkflowNode node,
                                   WorkflowBindingSnapshot bindings,
                                   boolean publishMode) {
        Long agentId = WorkflowNodeConfig.longValue(node.getConfig(), "agentId");
        if (agentId == null) {
            bindingIssue(result, publishMode, "AGENT_BINDING_MISSING",
                    "AGENT 节点「" + label(node) + "」未绑定 Agent", node);
            return;
        }
        WorkflowAgentBinding binding = bindings.agent(agentId);
        if (binding == null) {
            bindingIssue(result, publishMode, "AGENT_NOT_FOUND",
                    "AGENT 节点「" + label(node) + "」绑定的 Agent 不存在或已删除（id=" + agentId + "）",
                    node);
            return;
        }
        // W2 硬钉：ROUTER 会与画布扇出形成双重并行语义，任何模式下都不允许
        if ("ROUTER".equalsIgnoreCase(binding.getRunMode())) {
            addError(result, "AGENT_ROUTER_FORBIDDEN",
                    "AGENT 节点「" + label(node) + "」绑定了 ROUTER 类型的 Agent「"
                            + safeName(binding.getName(), agentId)
                            + "」；请用 PARALLEL_FORK/条件边表达扇出，或改绑叶子 Agent",
                    node);
        }
        if (binding.getStatus() == null || binding.getStatus() != 1) {
            bindingIssue(result, publishMode, "AGENT_DISABLED",
                    "AGENT 节点「" + label(node) + "」绑定的 Agent「"
                            + safeName(binding.getName(), agentId) + "」已停用",
                    node);
        }
    }

    /**
     * TOOL 绑定：存在且启用。
     */
    private static void checkTool(WorkflowValidationResult result,
                                  WorkflowNode node,
                                  WorkflowBindingSnapshot bindings,
                                  boolean publishMode) {
        Long toolId = WorkflowNodeConfig.longValue(node.getConfig(), "toolId");
        if (toolId == null) {
            bindingIssue(result, publishMode, "TOOL_BINDING_MISSING",
                    "TOOL 节点「" + label(node) + "」未绑定工具", node);
            return;
        }
        WorkflowToolBinding binding = bindings.tool(toolId);
        if (binding == null) {
            bindingIssue(result, publishMode, "TOOL_NOT_FOUND",
                    "TOOL 节点「" + label(node) + "」绑定的工具不存在或已删除（id=" + toolId + "）",
                    node);
            return;
        }
        if (binding.getStatus() == null || binding.getStatus() != 1) {
            bindingIssue(result, publishMode, "TOOL_DISABLED",
                    "TOOL 节点「" + label(node) + "」绑定的工具「"
                            + safeName(binding.getName(), toolId) + "」已停用",
                    node);
        }
    }

    /**
     * 非阻断提示：START 入边、END 出边、不可达、到不了 END、映射路径可疑。
     */
    private static void checkWarns(WorkflowValidationResult result,
                                   Map<String, WorkflowNode> nodeById,
                                   Map<String, WorkflowNodeType> nodeTypes,
                                   List<WorkflowNode> startNodes,
                                   List<WorkflowNode> endNodes,
                                   Map<String, List<WorkflowEdge>> outgoing,
                                   Map<String, List<WorkflowEdge>> incoming) {
        for (WorkflowNode node : nodeById.values()) {
            WorkflowNodeType type = nodeTypes.get(node.getId());
            if (type == WorkflowNodeType.START && !incoming.getOrDefault(node.getId(), List.of()).isEmpty()) {
                addWarn(result, "START_INCOMING", "START 节点「" + label(node) + "」存在入边，入口语义可疑", node);
            }
            if (type == WorkflowNodeType.END && !outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
                addWarn(result, "END_OUTGOING", "END 节点「" + label(node) + "」存在出边，出口语义可疑", node);
            }
            checkMapping(result, node);
        }

        Set<String> startIds = idsOf(startNodes);
        if (!startIds.isEmpty()) {
            Set<String> reachable = walk(startIds, outgoing);
            for (WorkflowNode node : nodeById.values()) {
                if (!reachable.contains(node.getId())) {
                    addWarn(result, "NODE_UNREACHABLE",
                            "节点「" + label(node) + "」从 START 不可达，可能永远不执行", node);
                }
            }
            Set<String> endIds = idsOf(endNodes);
            if (!endIds.isEmpty()) {
                Set<String> canReachEnd = walk(endIds, reverse(incoming));
                for (WorkflowNode node : nodeById.values()) {
                    if (reachable.contains(node.getId()) && !canReachEnd.contains(node.getId())) {
                        addWarn(result, "NO_PATH_TO_END",
                                "节点「" + label(node) + "」无法到达任何 END，流程可能无法收尾", node);
                    }
                }
            }
        }
    }

    /**
     * 映射路径提示：值里带 $ 但不是 $.input / $.outputs / $.vars 前缀时给 warn。
     * 只提示不阻断；字面量常量允许不带 $。
     */
    private static void checkMapping(WorkflowValidationResult result, WorkflowNode node) {
        checkMappingField(result, node, "inputMapping");
        checkMappingField(result, node, "outputMapping");
    }

    private static void checkMappingField(WorkflowValidationResult result,
                                          WorkflowNode node,
                                          String field) {
        JsonNode config = node.getConfig();
        if (config == null || !config.isObject()) {
            return;
        }
        JsonNode mapping = config.get(field);
        if (mapping == null || mapping.isNull()) {
            return;
        }
        if (!mapping.isObject()) {
            addWarn(result, "MAPPING_NOT_OBJECT",
                    "节点「" + label(node) + "」的 " + field + " 应为对象（键→取值路径）", node);
            return;
        }
        mapping.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                return;
            }
            String text = value.asText();
            if (text.contains("$") && !MAPPING_PATH.matcher(text).find()) {
                addWarn(result, "MAPPING_PATH_SUSPECT",
                        "节点「" + label(node) + "」映射 " + entry.getKey() + "=" + text
                                + " 不像 $.input / $.outputs / $.vars 路径", node);
            }
        });
    }

    /**
     * 广度优先收集可达节点。
     */
    private static Set<String> walk(Set<String> roots, Map<String, List<WorkflowEdge>> adjacency) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (WorkflowEdge edge : adjacency.getOrDefault(current, List.of())) {
                if (!visited.contains(edge.getTarget())) {
                    queue.addLast(edge.getTarget());
                }
            }
        }
        return visited;
    }

    /**
     * 反转邻接表，用于「能否到达 END」的反向遍历。
     */
    private static Map<String, List<WorkflowEdge>> reverse(Map<String, List<WorkflowEdge>> incoming) {
        // incoming 的语义是 target → 边；反向遍历要按 source 推回，故按边重建
        Map<String, List<WorkflowEdge>> reversed = new HashMap<>();
        for (List<WorkflowEdge> edges : incoming.values()) {
            for (WorkflowEdge edge : edges) {
                WorkflowEdge mirror = new WorkflowEdge();
                mirror.setId(edge.getId());
                mirror.setSource(edge.getTarget());
                mirror.setTarget(edge.getSource());
                mirror.setType(edge.getType());
                reversed.computeIfAbsent(mirror.getSource(), key -> new ArrayList<>()).add(mirror);
            }
        }
        return reversed;
    }

    private static Set<String> idsOf(List<WorkflowNode> nodes) {
        Set<String> ids = new LinkedHashSet<>();
        for (WorkflowNode node : nodes) {
            if (node != null && trimToNull(node.getId()) != null) {
                ids.add(node.getId().trim());
            }
        }
        return ids;
    }

    private static void bindingIssue(WorkflowValidationResult result,
                                     boolean publishMode,
                                     String code,
                                     String message,
                                     WorkflowNode node) {
        if (publishMode) {
            addError(result, code, message, node);
        } else {
            addWarn(result, code, message, node);
        }
    }

    private static void addError(WorkflowValidationResult result,
                                 String code,
                                 String message,
                                 WorkflowNode node) {
        result.getErrors().add(issue(code, message, node));
    }

    private static void addWarn(WorkflowValidationResult result,
                                String code,
                                String message,
                                WorkflowNode node) {
        result.getWarns().add(issue(code, message, node));
    }

    private static WorkflowIssue issue(String code, String message, WorkflowNode node) {
        if (node == null) {
            return new WorkflowIssue(code, message, null, null);
        }
        return new WorkflowIssue(code, message, trimToNull(node.getId()), trimToNull(node.getCode()));
    }

    private static String label(WorkflowNode node) {
        if (node == null) {
            return "?";
        }
        String code = trimToNull(node.getCode());
        if (code != null) {
            return code;
        }
        String id = trimToNull(node.getId());
        return id == null ? "?" : id;
    }

    private static String edgeLabel(WorkflowEdge edge) {
        String id = trimToNull(edge.getId());
        if (id != null) {
            return id;
        }
        return safeText(edge.getSource()) + "→" + safeText(edge.getTarget());
    }

    private static String safeName(String name, Long fallbackId) {
        String text = trimToNull(name);
        return text == null ? "id=" + fallbackId : text;
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
