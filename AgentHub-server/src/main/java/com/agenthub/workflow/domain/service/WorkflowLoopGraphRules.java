package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowIssue;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowValidationResult;
import com.agenthub.workflow.domain.service.WorkflowLoopPolicy.OnExceeded;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 循环声明的保存校验。所属域：workflow 的 domain。
 *
 * <p>未声明的环仍由 {@link WorkflowGraphValidator} 的环检测报出（已跳过指向循环节点的
 * {@code LOOP_BACK}）。这里只检查「声明了循环」时次数、出路、出入口是否齐，
 * 以及并行分叉和汇合没有被循环切成两半。
 */
public final class WorkflowLoopGraphRules {

    /** 与映射路径同一条正则，反馈路径写错时保存直接拒绝 */
    private static final Pattern MAPPING_PATH =
            Pattern.compile("^\\$\\.(input|outputs|vars)(\\.|\\[|$)");

    private WorkflowLoopGraphRules() {
    }

    /**
     * 检查图中每一个循环声明。
     *
     * @param result           校验结果，错误直接追加
     * @param nodeById         节点 id → 节点
     * @param nodeTypes        节点 id → 类型
     * @param outgoing         出边索引
     * @param incoming         入边索引
     * @param platformCap      平台生效的循环次数硬顶
     */
    public static void check(WorkflowValidationResult result,
                             Map<String, WorkflowNode> nodeById,
                             Map<String, WorkflowNodeType> nodeTypes,
                             Map<String, List<WorkflowEdge>> outgoing,
                             Map<String, List<WorkflowEdge>> incoming,
                             int platformCap) {
        int cap = platformCap < 1 ? WorkflowLoopPolicy.PLATFORM_CAP
                : Math.min(platformCap, WorkflowLoopPolicy.PLATFORM_CAP);
        List<String> loopIds = new ArrayList<>();
        for (WorkflowNode node : nodeById.values()) {
            if (nodeTypes.get(node.getId()) == WorkflowNodeType.LOOP) {
                loopIds.add(node.getId());
            }
        }
        checkEdgeRoles(result, nodeById, nodeTypes, outgoing);
        checkBackEdgeExclusive(result, nodeById, nodeTypes, outgoing);
        for (String loopId : loopIds) {
            checkOne(result, nodeById.get(loopId), nodeTypes, outgoing, incoming, cap);
        }
        checkForkPairs(result, nodeById, nodeTypes, outgoing, loopIds);
    }

    /**
     * 三种循环边只能出现在该出现的地方，避免把普通回边伪装成已声明。
     */
    private static void checkEdgeRoles(WorkflowValidationResult result,
                                       Map<String, WorkflowNode> nodeById,
                                       Map<String, WorkflowNodeType> nodeTypes,
                                       Map<String, List<WorkflowEdge>> outgoing) {
        for (List<WorkflowEdge> edges : outgoing.values()) {
            for (WorkflowEdge edge : edges) {
                WorkflowEdgeType type = WorkflowEdgeType.parse(edge.getType());
                WorkflowNode source = nodeById.get(edge.getSource());
                WorkflowNodeType sourceType = nodeTypes.get(edge.getSource());
                if ((type == WorkflowEdgeType.LOOP_BODY || type == WorkflowEdgeType.LOOP_EXIT)
                        && sourceType != WorkflowNodeType.LOOP) {
                    add(result, "LOOP_EDGE_SOURCE",
                            "边「" + edge.getId() + "」类型是 " + type + "，只能从循环节点拉出", source);
                }
                if (type == WorkflowEdgeType.LOOP_BACK
                        && nodeTypes.get(edge.getTarget()) != WorkflowNodeType.LOOP) {
                    add(result, "LOOP_BACK_TARGET",
                            "回到循环的边「" + edge.getId() + "」必须指向循环节点；"
                                    + "指向其它节点的回边属于未声明的环", source);
                }
            }
        }
    }

    /**
     * 单个循环节点：次数、出路、入口、回边、循环体不能是空的。
     */
    private static void checkOne(WorkflowValidationResult result, WorkflowNode node,
                                 Map<String, WorkflowNodeType> nodeTypes,
                                 Map<String, List<WorkflowEdge>> outgoing,
                                 Map<String, List<WorkflowEdge>> incoming,
                                 int cap) {
        if (!WorkflowLoopPolicy.iterationsPresent(node)) {
            add(result, "LOOP_MAX_MISSING",
                    "循环节点「" + label(node) + "」没有填写次数", node);
        } else if (WorkflowLoopPolicy.configuredIterations(node) == null) {
            add(result, "LOOP_MAX_INVALID",
                    "循环节点「" + label(node) + "」的次数必须是大于等于 1 的整数", node);
        } else if (WorkflowLoopPolicy.configuredIterations(node) > cap) {
            add(result, "LOOP_MAX_OVER_CAP",
                    "循环节点「" + label(node) + "」的次数 "
                            + WorkflowLoopPolicy.configuredIterations(node)
                            + " 超过平台硬顶 " + cap + "，图上填写不得超过硬顶", node);
        }
        OnExceeded outcome = WorkflowLoopPolicy.onExceeded(node);
        if (outcome == null) {
            add(result, "LOOP_ON_EXCEEDED_MISSING",
                    "循环节点「" + label(node) + "」没有配置超限出路（下游继续 / 转人工 / 失败）", node);
        }
        String feedbackPath = WorkflowLoopPolicy.feedbackPath(node);
        if (feedbackPath != null && !MAPPING_PATH.matcher(feedbackPath).find()) {
            add(result, "LOOP_FEEDBACK_PATH",
                    "循环节点「" + label(node) + "」的打回反馈路径必须是 $.input / $.outputs / $.vars",
                    node);
        }
        List<WorkflowEdge> out = outgoing.getOrDefault(node.getId(), List.of());
        long bodies = count(out, WorkflowEdgeType.LOOP_BODY);
        long exits = count(out, WorkflowEdgeType.LOOP_EXIT);
        if (bodies != 1) {
            add(result, "LOOP_BODY_EDGE",
                    "循环节点「" + label(node) + "」必须恰好有 1 条进入循环体的边，当前 " + bodies + " 条",
                    node);
        }
        long backs = incoming.getOrDefault(node.getId(), List.of()).stream()
                .filter(edge -> WorkflowEdgeType.parse(edge.getType()) == WorkflowEdgeType.LOOP_BACK)
                .count();
        if (backs < 1) {
            add(result, "LOOP_BACK_MISSING",
                    "循环节点「" + label(node) + "」没有声明回边（LOOP_BACK）。"
                            + "未声明的环不能保存", node);
        }
        if (outcome == OnExceeded.FAIL && exits > 0) {
            add(result, "LOOP_EXIT_UNEXPECTED",
                    "循环节点「" + label(node) + "」超限出路是失败，不能再画离开循环的边", node);
        } else if (outcome != null && outcome != OnExceeded.FAIL && exits != 1) {
            add(result, "LOOP_EXIT_EDGE",
                    "循环节点「" + label(node) + "」超限后要离开循环，必须恰好有 1 条离开边，当前 "
                            + exits + " 条", node);
        }
        if (outcome == OnExceeded.HUMAN) {
            for (WorkflowEdge edge : out) {
                if (WorkflowEdgeType.parse(edge.getType()) != WorkflowEdgeType.LOOP_EXIT) {
                    continue;
                }
                if (nodeTypes.get(edge.getTarget()) != WorkflowNodeType.HUMAN) {
                    add(result, "LOOP_EXIT_HUMAN",
                            "循环节点「" + label(node) + "」超限出路是转人工，离开边必须接到人工节点",
                            node);
                }
            }
        }
        if (bodies == 1 && backs >= 1 && WorkflowLoopBodies.of(node.getId(), outgoing).isEmpty()) {
            add(result, "LOOP_BODY_EMPTY",
                    "循环节点「" + label(node) + "」的进入边到不了声明回边，循环体是空的", node);
        }
    }

    /**
     * 非条件节点若同时有回边和另一条成功边，运行时会两条一起走。
     * 条件节点由选路保证只走一条，不在这里拦。
     */
    private static void checkBackEdgeExclusive(WorkflowValidationResult result,
                                               Map<String, WorkflowNode> nodeById,
                                               Map<String, WorkflowNodeType> nodeTypes,
                                               Map<String, List<WorkflowEdge>> outgoing) {
        for (Map.Entry<String, List<WorkflowEdge>> entry : outgoing.entrySet()) {
            if (nodeTypes.get(entry.getKey()) == WorkflowNodeType.CONDITION
                    || nodeTypes.get(entry.getKey()) == WorkflowNodeType.LOOP) {
                continue;
            }
            long backs = count(entry.getValue(), WorkflowEdgeType.LOOP_BACK);
            if (backs == 0) {
                continue;
            }
            long others = entry.getValue().stream()
                    .filter(edge -> {
                        WorkflowEdgeType type = WorkflowEdgeType.parse(edge.getType());
                        return type != WorkflowEdgeType.LOOP_BACK && type != WorkflowEdgeType.ERROR
                                && type != null;
                    })
                    .count();
            if (others > 0) {
                add(result, "LOOP_BACK_AMBIGUOUS",
                        "节点同时有回到循环的边和其它成功边，运行时会一边打回一边往下走。"
                                + "请改成条件节点，用一条回边做不通过的兜底",
                        nodeById.get(entry.getKey()));
            }
        }
    }

    /**
     * 同一并行组的分叉和汇合，对每一个循环要么都在体内，要么都在体外。
     */
    private static void checkForkPairs(WorkflowValidationResult result,
                                       Map<String, WorkflowNode> nodeById,
                                       Map<String, WorkflowNodeType> nodeTypes,
                                       Map<String, List<WorkflowEdge>> outgoing,
                                       List<String> loopIds) {
        if (loopIds.isEmpty()) {
            return;
        }
        Map<String, List<String>> forks = new LinkedHashMap<>();
        Map<String, List<String>> joins = new LinkedHashMap<>();
        for (WorkflowNode node : nodeById.values()) {
            WorkflowNodeType type = nodeTypes.get(node.getId());
            if (type != WorkflowNodeType.PARALLEL_FORK && type != WorkflowNodeType.PARALLEL_JOIN) {
                continue;
            }
            String group = WorkflowNodeConfig.textValue(node.getConfig(), "forkGroupId");
            if (group == null) {
                continue;
            }
            (type == WorkflowNodeType.PARALLEL_FORK ? forks : joins)
                    .computeIfAbsent(group, key -> new ArrayList<>()).add(node.getId());
        }
        for (Map.Entry<String, List<String>> entry : forks.entrySet()) {
            List<String> joinIds = joins.getOrDefault(entry.getKey(), List.of());
            for (String forkId : entry.getValue()) {
                List<String> forkLoops = WorkflowLoopBodies.containers(forkId, loopIds, outgoing);
                for (String joinId : joinIds) {
                    List<String> joinLoops = WorkflowLoopBodies.containers(joinId, loopIds, outgoing);
                    if (!forkLoops.equals(joinLoops)) {
                        add(result, "LOOP_FORK_SPLIT",
                                "并行组「" + entry.getKey() + "」的分叉和汇合没有一起留在同一个循环里，"
                                        + "会出现只扇出不汇合或重复汇合", nodeById.get(forkId));
                    }
                }
            }
        }
    }

    private static long count(List<WorkflowEdge> edges, WorkflowEdgeType type) {
        return edges.stream().filter(edge -> WorkflowEdgeType.parse(edge.getType()) == type).count();
    }

    private static void add(WorkflowValidationResult result, String code, String message, WorkflowNode node) {
        WorkflowIssue issue = new WorkflowIssue();
        issue.setCode(code);
        issue.setMessage(message);
        if (node != null) {
            issue.setNodeId(node.getId());
            issue.setNodeCode(node.getCode());
        }
        result.getErrors().add(issue);
    }

    private static String label(WorkflowNode node) {
        if (node == null) {
            return "未命名";
        }
        if (node.getName() != null && !node.getName().isBlank()) {
            return node.getName();
        }
        return node.getCode() == null ? node.getId() : node.getCode();
    }
}
