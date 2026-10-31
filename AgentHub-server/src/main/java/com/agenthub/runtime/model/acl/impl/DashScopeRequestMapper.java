package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedToolDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * DashScope 原生接口请求体映射器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么单独成类：原生协议最大的坑是<b>嵌套结构</b>——messages 在 input 里、采样与 tools 在 parameters 里，
 * 且必须显式要求 result_format=message 才能拿到工具调用。与 HTTP/流式收尾混在一起时极易看漏。
 * 本类只做纯数据搬运，不做 IO、不持有状态，可并发使用。
 */
final class DashScopeRequestMapper {

    private final ObjectMapper json;

    /**
     * @param json 序列化器，复用容器配置
     */
    DashScopeRequestMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 组装原生请求体：model + input.messages + parameters{result_format …}。
     * 与 OpenAI 兼容实现的差别就是这层嵌套——不是平铺在根上。
     *
     * @param request 统一请求
     * @param model   模型实例 id
     * @param stream  是否流式；流式时必须开 incremental_output 才是增量帧
     * @return JSON 文本
     */
    String build(UnifiedChatRequest request, String model, boolean stream) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);

        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        if (request.getMessages() != null) {
            for (UnifiedMessage message : request.getMessages()) {
                messages.add(toMessageNode(message));
            }
        }

        ObjectNode parameters = root.putObject("parameters");
        // 不指定 result_format 时原生接口返回 output.text，工具调用与 role/usage 全部丢失
        parameters.put("result_format", "message");
        if (stream) {
            parameters.put("incremental_output", true);
        }
        if (request.getTemperature() != null) {
            parameters.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            parameters.put("max_tokens", request.getMaxTokens());
        }
        // 原生接口把工具定义放在 parameters 内，不同于 OpenAI 的顶层 tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode tools = parameters.putArray("tools");
            for (UnifiedToolDef tool : request.getTools()) {
                ObjectNode item = tools.addObject();
                item.put("type", "function");
                ObjectNode function = item.putObject("function");
                function.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    function.put("description", tool.getDescription());
                }
                function.set("parameters", readSchema(tool.getParametersJsonSchema()));
            }
        }
        return root.toString();
    }

    /**
     * 统一消息 → 原生消息。
     * 原生 message 结构与 OpenAI 近似（role/content/tool_calls），差别是工具结果消息用 name 关联，
     * 这里同时带上 name 与 tool_call_id，两个字段都发可兼容不同模型版本，多余字段会被上游忽略。
     *
     * @param message 统一消息
     * @return 原生消息节点
     */
    private ObjectNode toMessageNode(UnifiedMessage message) {
        ObjectNode node = json.createObjectNode();
        node.put("role", message.getRole());
        if (message.getContent() != null) {
            node.put("content", message.getContent());
        }
        if (message.getName() != null && !message.getName().isBlank()) {
            node.put("name", message.getName());
        }
        if (message.getToolCallId() != null && !message.getToolCallId().isBlank()) {
            node.put("tool_call_id", message.getToolCallId());
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (UnifiedToolCall call : message.getToolCalls()) {
                ObjectNode item = calls.addObject();
                item.put("id", call.getId());
                item.put("type", "function");
                ObjectNode function = item.putObject("function");
                function.put("name", call.getName());
                function.put("arguments", call.getArguments() == null ? "{}" : call.getArguments());
            }
        }
        return node;
    }

    /**
     * 解析工具参数 schema。空值给宽松 object schema，避免上游 400。
     *
     * @param schema JSON Schema 原文，可空
     * @return 可内联的 schema 节点
     * @throws IllegalArgumentException schema 不是合法 JSON
     */
    private JsonNode readSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            ObjectNode empty = json.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            return empty;
        }
        try {
            return json.readTree(schema);
        } catch (Exception ex) {
            throw new IllegalArgumentException("工具参数 schema 不是合法 JSON");
        }
    }
}
