package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowIssue;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 保存 / 校验 / 发布阶段的子图静态检查。所属域：workflow 的 application。
 *
 * <p>职责：按钉死的发布快照递归检查 SUBGRAPH 链路——
 * 版本是否仍在、是否属于所绑工作流、是否同租户、调用链是否成环、嵌套是否超过 3 层。
 * 运行时限的问题由 {@link WorkflowPublishCheck} 另算，这里只管子图。
 *
 * <p>关键约束：
 * <ul>
 *   <li>只读：不写库、不改图；</li>
 *   <li>文案面向用户，不出现主机、端口、路径、异常类名或堆栈；</li>
 *   <li>运行时仍会再校验一次：这里能拦的拦下，手改库 / 历史发布由引擎兜底。</li>
 * </ul>
 */
@Component
public class WorkflowSubgraphGuard {

    /** 单次检查展开的子图节点上限：防御坏数据 / 极端图，不作为业务限制 */
    private static final int MAX_WALK = 200;

    private final WorkflowSubgraphResolver resolver;

    /**
     * @param resolver 钉死发布版本解析器
     */
    public WorkflowSubgraphGuard(WorkflowSubgraphResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 检查从当前图出发的全部子图链路。
     *
     * @param tenantId 当前工作流所属租户
     * @param graph    已解析的图
     * @return errors；空表示通过
     */
    public List<WorkflowIssue> check(long tenantId, WorkflowGraph graph) {
        List<WorkflowIssue> issues = new ArrayList<>();
        if (graph == null) {
            return issues;
        }
        Set<String> visited = new HashSet<>();
        walk(tenantId, graph, new ArrayList<>(), new ArrayList<>(), visited, issues, 0);
        return issues;
    }

    /**
     * 递归：只沿 SUBGRAPH 节点走，普通节点与循环不展开。
     */
    private void walk(long tenantId, WorkflowGraph graph, List<String> pathLabels,
                      List<Long> workflowStack, Set<String> visited, List<WorkflowIssue> issues,
                      int depth) {
        if (graph == null || issues.size() >= MAX_WALK) {
            return;
        }
        for (WorkflowNode node : graph.getNodes() == null ? List.<WorkflowNode>of() : graph.getNodes()) {
            if (node == null || WorkflowNodeType.parse(node.getType()) != WorkflowNodeType.SUBGRAPH) {
                continue;
            }
            Long subWorkflowId = WorkflowNodeConfig.longValue(node.getConfig(), "subWorkflowId");
            Long subReleaseId = WorkflowNodeConfig.longValue(node.getConfig(), "subReleaseId");
            if (subWorkflowId == null || subReleaseId == null) {
                // 必填缺失由图校验器直接报；这里不重复制造两条错误
                continue;
            }
            String nodeLabel = labelOf(node);
            List<String> nextLabels = append(pathLabels, nodeLabel);
            // 环：同一工作流定义在调用栈上出现两次
            if (workflowStack.contains(subWorkflowId)) {
                issues.add(new WorkflowIssue("SUBGRAPH_CYCLE",
                        "子图调用出现循环：" + String.join(" → ", nextLabels), null, null));
                continue;
            }
            // 层级：只计 SUBGRAPH 层，硬顶 3；当前图已经是最外层，子图从第 1 层开始
            if (depth + 1 > WorkflowScope.MAX_DEPTH) {
                issues.add(new WorkflowIssue("SUBGRAPH_DEPTH",
                        "子图嵌套超过 " + WorkflowScope.MAX_DEPTH + " 层："
                                + String.join(" → ", nextLabels), null, null));
                continue;
            }
            WorkflowSubgraphSnapshot snapshot;
            try {
                snapshot = resolver.resolve(tenantId, subWorkflowId, subReleaseId);
            } catch (WorkflowNodeFailure ex) {
                issues.add(new WorkflowIssue("SUBGRAPH_UNUSABLE",
                        "子图「" + String.join(" → ", nextLabels) + "」不可用：" + ex.getMessage(),
                        depth == 0 ? node.getId() : null, depth == 0 ? node.getCode() : null));
                continue;
            } catch (RuntimeException ex) {
                issues.add(new WorkflowIssue("SUBGRAPH_UNUSABLE",
                        "子图「" + String.join(" → ", nextLabels) + "」不可用，请检查绑定的发布版本",
                        depth == 0 ? node.getId() : null, depth == 0 ? node.getCode() : null));
                continue;
            }
            // 同一发布版本在相同层级已展开过：不再重复走，避免菱形引用把结果放大
            String visitKey = (depth + 1) + ":" + subReleaseId;
            if (!visited.add(visitKey)) {
                continue;
            }
            List<Long> nextStack = new ArrayList<>(workflowStack);
            nextStack.add(subWorkflowId);
            walk(tenantId, snapshot.graph(), nextLabels, nextStack, visited, issues, depth + 1);
        }
    }

    private static List<String> append(List<String> path, String label) {
        List<String> next = new ArrayList<>(path == null ? List.of() : path);
        next.add(label);
        return next;
    }

    private static String labelOf(WorkflowNode node) {
        if (node.getName() != null && !node.getName().isBlank()) {
            return node.getName().trim();
        }
        return node.getCode() == null || node.getCode().isBlank() ? "子图节点" : node.getCode().trim();
    }
}
