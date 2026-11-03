package com.agenthub.workflow.domain.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 节点 / 边 config JSON 的读取助手。所属域：workflow 的 domain。
 * <p>调用方：图校验器与指纹计算器。只读、无状态、线程安全。
 * <p>为什么不用强类型 DTO：graph_json 由画布前端维护，P0 允许未知字段透传；
 * 校验只关心少数关键字段，读不到时返回 null 交由校验器给中文 error，而不是解析期直接 500。
 */
public final class WorkflowNodeConfig {

    private WorkflowNodeConfig() {
    }

    /**
     * 读取整数字段，容忍数字与纯数字字符串。
     *
     * @param config 节点 / 边 config，可空
     * @param field  字段名，如 agentId / toolId
     * @return 解析出的 long；缺失或非法返回 null
     */
    public static Long longValue(JsonNode config, String field) {
        if (config == null || !config.isObject()) {
            return null;
        }
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取文本字段，首尾空白会被去掉。
     *
     * @param config config，可空
     * @param field  字段名
     * @return 非空文本；缺失或空白返回 null
     */
    public static String textValue(JsonNode config, String field) {
        if (config == null || !config.isObject()) {
            return null;
        }
        JsonNode value = config.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 读取对象字段（如 inputMapping / outputMapping）。
     *
     * @param config config，可空
     * @param field  字段名
     * @return 对象节点；缺失或不是对象返回 null
     */
    public static JsonNode objectValue(JsonNode config, String field) {
        if (config == null || !config.isObject()) {
            return null;
        }
        JsonNode value = config.get(field);
        return value != null && value.isObject() ? value : null;
    }
}
