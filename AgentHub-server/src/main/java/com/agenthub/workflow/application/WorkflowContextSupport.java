package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import com.agenthub.workflow.domain.service.WorkflowNodeInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流调度的图 / 上下文读写。所属域：workflow 的 application。
 *
 * <p>从 {@code WorkflowEngine} 搬出，职责只有一件事：把 run 的输入、已完成节点产出、
 * 节点配置映射与选中边整理成调度器直接可用的对象。不落库、不发布卡片、不判超时。
 *
 * <p>口径保持不变：入参映射优先、缺失时回落 {@code ctx.input}；产出只取 SUCCESS 节点；
 * 选中边按逗号拆分；普通成功节点的默认出边排除 ERROR。
 */
public class WorkflowContextSupport {

    /** 全站 ObjectMapper：解析 / 组装节点 JSON。 */
    private final ObjectMapper json;

    /**
     * @param json 全站 ObjectMapper
     */
    public WorkflowContextSupport(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 读 run 的输入 JSON。空 / 非对象都按空对象处理，调度不因脏数据中断。
     *
     * @param raw 原始 JSON，可空
     * @return 可变空对象或原对象
     */
    public ObjectNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        JsonNode node = parse(raw);
        return node != null && node.isObject() ? (ObjectNode) node : json.createObjectNode();
    }

    /**
     * 宽松解析：空串或坏 JSON 返回 null，调用方按「没有产出」处理。
     *
     * @param raw 原始 JSON，可空
     * @return 解析结果；失败为空
     */
    public JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 取已完成节点产出，按节点编码挂到 {@code outputs}。
     *
     * @param runtime 图
     * @param rows    与图顺序一致的运行态行
     * @return 节点编码 → 产出
     */
    public ObjectNode collectOutputs(WorkflowRuntimeGraph runtime, List<WorkflowNodeRun> rows) {
        ObjectNode outputs = json.createObjectNode();
        Map<String, String> codeById = new LinkedHashMap<>();
        for (WorkflowNode node : runtime.nodes()) {
            codeById.put(node.getId(), node.getCode());
        }
        for (WorkflowNodeRun row : rows) {
            if (WorkflowNodeRunStatus.parse(row.getStatus()) != WorkflowNodeRunStatus.SUCCESS) {
                continue;
            }
            String code = codeById.get(row.getNodeId());
            JsonNode produced = parse(row.getOutputJson());
            if (code != null && !code.isBlank() && produced != null) {
                outputs.set(code, produced);
            }
        }
        return outputs;
    }

    /**
     * 解析节点实际入参：{@code inputMapping} 有产出就用它，否则回落整份 {@code ctx.input}。
     *
     * @param ctx    当前上下文
     * @param config 节点配置；可空
     * @return 节点入参对象
     */
    public ObjectNode inputOf(ObjectNode ctx, JsonNode config) {
        ObjectNode mapped = WorkflowNodeInputs.mapped(ctx,
                WorkflowNodeConfig.objectValue(config, "inputMapping"));
        if (!mapped.isEmpty()) {
            return mapped;
        }
        JsonNode input = ctx.get("input");
        if (input instanceof ObjectNode object) {
            return object.deepCopy();
        }
        ObjectNode wrapper = json.createObjectNode();
        wrapper.set("input", input);
        return wrapper;
    }

    /**
     * 节点行里已选中的出边 id 集合。
     *
     * @param row 运行态行
     * @return 选中边 id；空串为空集合
     */
    public Set<String> selectedEdgeIds(WorkflowNodeRun row) {
        Set<String> ids = new LinkedHashSet<>();
        if (row.getSelectedEdges() == null || row.getSelectedEdges().isBlank()) {
            return ids;
        }
        for (String part : row.getSelectedEdges().split(",")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        return ids;
    }

    /**
     * 多条边 id 拼成落库用的逗号串（跳过空 id）。
     *
     * @param edges 边集合，可空
     * @return 逗号串；空集合为空串
     */
    public String joinIds(List<WorkflowEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (WorkflowEdge edge : edges) {
            if (edge.getId() == null || edge.getId().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(edge.getId());
        }
        return builder.toString();
    }

    /**
     * 普通成功节点的出边：除 ERROR 外全部选中（条件分支的选路由 CONDITION 自己决定）。
     *
     * @param runtime 图
     * @param node    当前节点
     * @return 默认选中边
     */
    public List<WorkflowEdge> defaultEdges(WorkflowRuntimeGraph runtime, WorkflowNode node) {
        List<WorkflowEdge> edges = new ArrayList<>();
        for (WorkflowEdge edge : runtime.outgoing(node.getId())) {
            if (WorkflowEdgeType.parseOrDefault(edge.getType()) != WorkflowEdgeType.ERROR) {
                edges.add(edge);
            }
        }
        return edges;
    }
}
