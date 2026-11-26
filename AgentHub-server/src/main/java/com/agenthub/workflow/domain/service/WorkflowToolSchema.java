package com.agenthub.workflow.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具入参 schema 的只读投影。所属域：workflow 的 domain。
 * <p>调用方：绑定解析（WorkflowBindingResolver）与图校验器。
 * <p>口径与前端出槽一致：只取 JSON Schema 的顶层 {@code properties}，与 {@code required}
 * 取交集；嵌套对象不展开，避免把一张参数表拆成一串子槽。
 * <p>纯函数、无状态、线程安全；坏 schema 一律按「没有定义」处理，不向调用方抛异常。
 */
public final class WorkflowToolSchema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowToolSchema() {
    }

    /**
     * 解析工具的顶层必填参数名。
     * <p>只返回同时出现在 {@code properties} 与 {@code required} 里的名字：
     * 画布按 properties 出槽，required 里多出来的名字没有对应槽可配，不能据此拦截发布。
     *
     * @param schemaJson 工具项的 input_schema_json 原文，可空
     * @return 必填参数名（按 schema 的 required 顺序、去重）；空 schema / 非法 JSON 返回空列表
     */
    public static List<String> requiredInputs(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(schemaJson);
        } catch (JsonProcessingException ex) {
            // 坏 schema 不阻断编辑 / 校验：没有定义就等于没有必填约束
            return List.of();
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        JsonNode properties = root.get("properties");
        JsonNode required = root.get("required");
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
