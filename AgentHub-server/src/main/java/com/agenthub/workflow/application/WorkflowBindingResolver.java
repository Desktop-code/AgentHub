package com.agenthub.workflow.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowAgentBinding;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowToolBinding;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 校验期绑定解析。所属域：workflow 的 application。
 * <p>职责：把图中引用到的 agentId / toolId 一次性查成只读快照，交给纯校验器判断。
 * <p>边界：只读；不调用 Agent 执行器、不调模型、不修改绑定；查不到就是「不存在」。
 * 线程：无状态，可并发调用。
 */
@Component
public class WorkflowBindingResolver {

    private final AgentDefRepository agents;
    private final ToolItemRepository tools;

    /**
     * @param agents Agent 定义仓储（只读查询）
     * @param tools  工具项仓储（只读查询）
     */
    public WorkflowBindingResolver(AgentDefRepository agents, ToolItemRepository tools) {
        this.agents = agents;
        this.tools = tools;
    }

    /**
     * 解析图中全部绑定引用。
     *
     * @param graph 图；null 返回空快照
     * @return agentId / toolId 到只读绑定的映射
     */
    public WorkflowBindingSnapshot resolve(WorkflowGraph graph) {
        Map<Long, WorkflowAgentBinding> agentBindings = new HashMap<>();
        Map<Long, WorkflowToolBinding> toolBindings = new HashMap<>();
        if (graph == null || graph.getNodes() == null) {
            return new WorkflowBindingSnapshot(agentBindings, toolBindings);
        }
        for (WorkflowNode node : graph.getNodes()) {
            if (node == null) {
                continue;
            }
            WorkflowNodeType type = WorkflowNodeType.parse(node.getType());
            if (type == WorkflowNodeType.AGENT) {
                Long agentId = WorkflowNodeConfig.longValue(node.getConfig(), "agentId");
                if (agentId != null && !agentBindings.containsKey(agentId)) {
                    agents.findById(agentId).ifPresent(def -> agentBindings.put(agentId, toAgent(def)));
                }
            } else if (type == WorkflowNodeType.TOOL) {
                Long toolId = WorkflowNodeConfig.longValue(node.getConfig(), "toolId");
                if (toolId != null && !toolBindings.containsKey(toolId)) {
                    tools.findById(toolId).ifPresent(item -> toolBindings.put(toolId, toTool(item)));
                }
            }
        }
        return new WorkflowBindingSnapshot(agentBindings, toolBindings);
    }

    private WorkflowAgentBinding toAgent(AgentDef def) {
        return new WorkflowAgentBinding(def.getId(), def.getName(), def.getRunMode(), def.getStatus());
    }

    private WorkflowToolBinding toTool(ToolItem item) {
        return new WorkflowToolBinding(item.getId(), item.getName(), item.getStatus());
    }
}
