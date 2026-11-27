package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流入参声明的只读投影。所属域：workflow 的 domain。
 * <p>声明落在 START 节点的 config {@code inputSchema} 上（顶层 properties / required / title /
 * description），随 graph_json 一起进发布快照，因此「某个版本声明了什么」永远以该版本的
 * graph_json 为准，不受后来草稿改动影响。
 * <p>口径与 AGENT / TOOL 出槽一致：只取顶层 {@code properties}，并与 {@code required} 取交集；
 * 必填但没有对应槽的名字不能拿来拦发布。
 * <p>纯函数、无状态、线程安全；坏数据一律按「没有声明」处理，不抛业务异常。
 */
public final class WorkflowInputDeclaration {

    /** START 节点 config 里的声明字段名（与前端画布约定一致） */
    public static final String START_CONFIG_KEY = "inputSchema";

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowInputDeclaration() {
    }

    /**
     * 取图里 START 节点上的入参声明。
     * <p>多 START 属于非法图，这里按节点顺序取第一个非空声明，只为给出可读提示与出槽；
     * 「只能有 1 个 START」由校验器单独报错。
     *
     * @param graph 图，可空
     * @return 声明对象；没有 START / 没有声明 / 结构不是对象时返回 null
     */
    public static JsonNode startDeclaration(WorkflowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        for (WorkflowNode node : graph.getNodes()) {
            if (node == null || WorkflowNodeType.parse(node.getType()) != WorkflowNodeType.START) {
                continue;
            }
            JsonNode declaration = WorkflowNodeConfig.objectValue(node.getConfig(), START_CONFIG_KEY);
            if (declaration != null) {
                return declaration;
            }
        }
        return null;
    }

    /**
     * 解析一段声明 JSON 文本（Agent 入参声明存的是字符串）。
     *
     * @param json 声明 JSON 文本，可空
     * @return 声明对象；空白 / 非法 JSON / 非对象返回 null
     */
    public static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * 声明里的必填键：{@code properties} 与 {@code required} 取交集。
     *
     * @param declaration 声明对象，可空
     * @return 必填键（按 required 顺序、去重）；无声明 / 结构不对返回空列表
     */
    public static List<String> requiredKeys(JsonNode declaration) {
        if (declaration == null || !declaration.isObject()) {
            return List.of();
        }
        JsonNode properties = declaration.get("properties");
        JsonNode required = declaration.get("required");
        if (properties == null || !properties.isObject() || required == null || !required.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : required) {
            if (!item.isTextual()) {
                continue;
            }
            String key = item.asText().trim();
            if (key.isEmpty() || !properties.has(key) || result.contains(key)) {
                continue;
            }
            result.add(key);
        }
        return result;
    }
}
