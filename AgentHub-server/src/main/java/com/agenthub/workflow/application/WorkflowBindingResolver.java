package com.agenthub.workflow.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.service.AgentInputSchema;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowAgentBinding;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowSubgraphBinding;
import com.agenthub.workflow.domain.model.WorkflowToolBinding;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowInputDeclaration;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.domain.service.WorkflowToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 校验期绑定解析。所属域：workflow 的 application。
 * <p>职责：把图中引用到的 agentId / toolId / 子图 releaseId 一次性查成只读快照，
 * 交给纯校验器判断。子图只认「被钉死的那条发布版本」的 START 入参声明，不读对方草稿。
 * <p>边界：只读；不调用 Agent 执行器、不调模型、不修改绑定；查不到 / 跨租户就是「不存在」。
 * 线程：无状态，可并发调用。
 */
@Component
public class WorkflowBindingResolver {

    private final AgentDefRepository agents;
    private final ToolItemRepository tools;
    private final WorkflowDefRepository defs;
    private final WorkflowReleaseRepository releases;
    private final WorkflowTenantProvider tenantProvider;
    private final WorkflowGraphCodec codec;

    /**
     * @param agents         Agent 定义仓储（只读查询）
     * @param tools          工具项仓储（只读查询）
     * @param defs           工作流定义仓储（子图跨租户校验）
     * @param releases       发布版本仓储（取钉死版本的 graph_json）
     * @param tenantProvider 当前租户（子图不允许跨租户）
     * @param codec          graph_json 解析
     */
    public WorkflowBindingResolver(AgentDefRepository agents, ToolItemRepository tools,
                                   WorkflowDefRepository defs, WorkflowReleaseRepository releases,
                                   WorkflowTenantProvider tenantProvider, WorkflowGraphCodec codec) {
        this.agents = agents;
        this.tools = tools;
        this.defs = defs;
        this.releases = releases;
        this.tenantProvider = tenantProvider;
        this.codec = codec;
    }

    /**
     * 解析图中全部绑定引用。
     *
     * @param graph 图；null 返回空快照
     * @return agentId / toolId / releaseId 到只读绑定的映射
     */
    public WorkflowBindingSnapshot resolve(WorkflowGraph graph) {
        Map<Long, WorkflowAgentBinding> agentBindings = new HashMap<>();
        Map<Long, WorkflowToolBinding> toolBindings = new HashMap<>();
        Map<Long, WorkflowSubgraphBinding> subgraphBindings = new HashMap<>();
        if (graph == null || graph.getNodes() == null) {
            return new WorkflowBindingSnapshot(agentBindings, toolBindings, subgraphBindings);
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
            } else if (type == WorkflowNodeType.SUBGRAPH) {
                Long releaseId = WorkflowNodeConfig.longValue(node.getConfig(), "subReleaseId");
                if (releaseId != null && !subgraphBindings.containsKey(releaseId)) {
                    WorkflowSubgraphBinding binding = toSubgraph(releaseId);
                    if (binding != null) {
                        subgraphBindings.put(releaseId, binding);
                    }
                }
            }
        }
        return new WorkflowBindingSnapshot(agentBindings, toolBindings, subgraphBindings);
    }

    /**
     * Agent 绑定：附带入参声明里的必填键，供发布校验判断必填槽是否配了来源。
     *
     * @param def Agent 定义
     * @return 只读快照
     */
    private WorkflowAgentBinding toAgent(AgentDef def) {
        JsonNode declaration = AgentInputSchema.read(def.getInputSchemaJson());
        return new WorkflowAgentBinding(def.getId(), def.getName(), def.getRunMode(), def.getStatus(),
                AgentInputSchema.requiredKeys(declaration));
    }

    /**
     * 工具绑定：只投影顶层必填参数，画布出槽与发布校验都按同一份 schema 口径。
     *
     * @param item 工具项
     * @return 只读快照
     */
    private WorkflowToolBinding toTool(ToolItem item) {
        return new WorkflowToolBinding(item.getId(), item.getName(), item.getStatus(),
                WorkflowToolSchema.requiredInputs(item.getInputSchemaJson()));
    }

    /**
     * 子图绑定：按 releaseId 取版本，再核对工作流存在且同租户，最后从该版本的 graph_json
     * 里投影 START 入参声明。
     * <p>版本被删、定义被删、跨租户都返回 null，让校验器按「版本不可用」给中文 error；
     * 该版本的图万一解析失败也只降级成「没有必填约束」，不让校验 API 500。
     *
     * @param releaseId 发布版本 id
     * @return 只读快照；不可用时 null
     */
    private WorkflowSubgraphBinding toSubgraph(Long releaseId) {
        WorkflowRelease release = releases.findById(releaseId).orElse(null);
        if (release == null) {
            return null;
        }
        WorkflowDef def = defs.findById(release.getWorkflowId()).orElse(null);
        // 跨租户按「不存在」处理：既能拦住拼 id 的越权绑定，也不泄露对方是否存在
        if (def == null || def.getTenantId() == null
                || def.getTenantId() != tenantProvider.currentTenantId()) {
            return null;
        }
        JsonNode declaration = null;
        try {
            declaration = WorkflowInputDeclaration.startDeclaration(codec.parse(release.getGraphJson()));
        } catch (RuntimeException ex) {
            // 已发布的图正常不会坏；坏了大不了没有必填约束，不能让校验接口整体失败
            declaration = null;
        }
        return new WorkflowSubgraphBinding(release.getId(), release.getWorkflowId(), release.getVersion(),
                def.getName(), WorkflowInputDeclaration.requiredKeys(declaration));
    }
}
