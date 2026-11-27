package com.agenthub.workflow.domain.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 图校验所需的绑定快照。所属域：workflow 的 domain。
 * <p>键是 agentId / toolId / releaseId；查不到即「不存在、已删除或跨租户」。实现无状态、可并发读。
 * <p>为什么单独做快照：校验器要写成纯函数（可单测、不连库），由应用层负责查库。
 */
public class WorkflowBindingSnapshot {

    private final Map<Long, WorkflowAgentBinding> agents;
    private final Map<Long, WorkflowToolBinding> tools;
    private final Map<Long, WorkflowSubgraphBinding> subgraphs;

    /**
     * 只有 Agent / 工具的快照；子图按「未绑定 / 版本不可用」处理。
     *
     * @param agents agentId → 绑定
     * @param tools  toolId → 绑定
     */
    public WorkflowBindingSnapshot(Map<Long, WorkflowAgentBinding> agents,
                                   Map<Long, WorkflowToolBinding> tools) {
        this(agents, tools, null);
    }

    /**
     * 完整快照。
     *
     * @param agents   agentId → 绑定
     * @param tools    toolId → 绑定
     * @param subgraphs releaseId → 子图钉死版本
     */
    public WorkflowBindingSnapshot(Map<Long, WorkflowAgentBinding> agents,
                                   Map<Long, WorkflowToolBinding> tools,
                                   Map<Long, WorkflowSubgraphBinding> subgraphs) {
        this.agents = agents == null ? Map.of() : Map.copyOf(agents);
        this.tools = tools == null ? Map.of() : Map.copyOf(tools);
        this.subgraphs = subgraphs == null ? Map.of() : Map.copyOf(subgraphs);
    }

    /**
     * 空快照：单测里验证「绑定目标不存在」分支时使用。
     *
     * @return 不含任何绑定的快照
     */
    public static WorkflowBindingSnapshot empty() {
        return new WorkflowBindingSnapshot(new HashMap<>(), new HashMap<>());
    }

    /**
     * @param agentId Agent id，可空
     * @return 绑定；不存在返回 null
     */
    public WorkflowAgentBinding agent(Long agentId) {
        return agentId == null ? null : agents.get(agentId);
    }

    /**
     * @param toolId 工具项 id，可空
     * @return 绑定；不存在返回 null
     */
    public WorkflowToolBinding tool(Long toolId) {
        return toolId == null ? null : tools.get(toolId);
    }

    /**
     * @param releaseId 被调工作流的发布版本 id，可空
     * @return 绑定；不存在（版本被删 / 跨租户 / 未选）返回 null
     */
    public WorkflowSubgraphBinding subgraph(Long releaseId) {
        return releaseId == null ? null : subgraphs.get(releaseId);
    }
}
