package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedToolDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Anthropic Messages 请求体映射器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么单独成类：请求侧的形状差异是 Anthropic 最容易被写错的地方（system 顶层、tool_result block、
 * input_schema），把它与 HTTP / 流式收尾混在一个类里会难以复核。
 * 本类只做纯数据搬运，不做 IO、不持有状态，可并发使用。
 */
final class AnthropicRequestMapper {

    /**
     * max_tokens 缺省值。Anthropic 该字段必填，不给就 400；
     * 取 1024 是为了「够用且不会因余额不足被拒」，业务传了就以业务为准。
     */
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final ObjectMapper json;

    /**
     * @param json 序列化器，复用容器配置
     */
    AnthropicRequestMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 组装 Messages 请求体。
     * 与本族最大差别：system 提到顶层、tools 用 input_schema、tool 结果变成 user 里的 tool_result block。
     *
     * @param request  统一请求
     * @param model    模型实例 id
     * @param stream   是否流式
     * @return JSON 文本
     */
    String build(UnifiedChatRequest request, String model, boolean stream) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        // max_tokens 是 Anthropic 必填项，业务未指定时给默认值而不是省略
        root.put("max_tokens", request.getMaxTokens() == null ? DEFAULT_MAX_TOKENS : request.getMaxTokens());
        root.put("stream", stream);
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }

        List<UnifiedMessage> messages = request.getMessages() == null ? List.of() : request.getMessages();
        // 把 system 消息抽出来合并成顶层字段；多条之间用空行分隔，保持原有顺序语义
        String system = joinSystemPrompts(messages);
        if (system != null && !system.isBlank()) {
            root.put("system", system);
        }

        ArrayNode messageNodes = root.putArray("messages");
        for (UnifiedMessage message : messages) {
            // system 已提到顶层，不能再留在 messages 里（Anthropic 不接受该角色）
            if (isSystemRole(message.getRole())) {
                continue;
            }
            messageNodes.add(toMessageNode(message));
        }

        // 工具定义：顶层 tools[]，参数 schema 字段名是 input_schema
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (UnifiedToolDef tool : request.getTools()) {
                ObjectNode item = tools.addObject();
                item.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    item.put("description", tool.getDescription());
                }
                item.set("input_schema", readSchema(tool.getParametersJsonSchema()));
            }
        }
        return root.toString();
    }

    /**
     * 合并所有 system 消息。Anthropic 只接受一个 system 字符串，
     * 多条（业务常把平台前缀与 Agent 提示词分开）必须拼成一段，否则会丢提示词。
     *
     * @param messages 完整消息序列
     * @return 合并后的 system 文本；没有 system 消息时返回 null
     */
    private String joinSystemPrompts(List<UnifiedMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (UnifiedMessage message : messages) {
            if (!isSystemRole(message.getRole())) {
                continue;
            }
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(message.getContent());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /**
     * @param role 消息角色
     * @return 是否是 system 角色（大小写不敏感）
     */
    private static boolean isSystemRole(String role) {
        return role != null && "system".equalsIgnoreCase(role.trim());
    }

    /**
     * 统一消息 → Anthropic 消息。
     * 关键差异：工具结果不能是 role=tool，必须转成 user 消息里的 tool_result block；
     * assistant 的历史工具调用是 tool_use block。这两条不转会被上游 400。
     *
     * @param message 统一消息
     * @return 厂商消息节点
     */
    private ObjectNode toMessageNode(UnifiedMessage message) {
        ObjectNode node = json.createObjectNode();
        // 统一的 tool 角色在 Anthropic 侧一律表示为 user + tool_result
        boolean toolResult = "tool".equalsIgnoreCase(message.getRole());
        node.put("role", toolResult ? "user" : message.getRole());

        if (toolResult) {
            ArrayNode blocks = node.putArray("content");
            ObjectNode block = blocks.addObject();
            block.put("type", "tool_result");
            if (message.getToolCallId() != null && !message.getToolCallId().isBlank()) {
                block.put("tool_use_id", message.getToolCallId());
            }
            // tool_result 的 content 是字符串即可（多段图片等能力本期不做）
            block.put("content", message.getContent() == null ? "" : message.getContent());
            return node;
        }

        boolean hasToolCalls = message.getToolCalls() != null && !message.getToolCalls().isEmpty();
        if (!hasToolCalls) {
            // 纯文本消息：content 直接用字符串，比单元素 block 数组更省 token
            node.put("content", message.getContent() == null ? "" : message.getContent());
            return node;
        }

        // 有工具调用的 assistant 消息：content 必须是 block 数组（text + tool_use）
        ArrayNode blocks = node.putArray("content");
        if (message.getContent() != null && !message.getContent().isBlank()) {
            ObjectNode textBlock = blocks.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", message.getContent());
        }
        for (UnifiedToolCall call : message.getToolCalls()) {
            ObjectNode toolBlock = blocks.addObject();
            toolBlock.put("type", "tool_use");
            toolBlock.put("id", call.getId());
            toolBlock.put("name", call.getName());
            // tool_use 的 input 必须是对象而不是字符串：这里把统一层的原始 JSON 字符串解析回对象
            toolBlock.set("input", readArguments(call.getArguments()));
        }
        return node;
    }

    /**
     * 解析工具调用参数。统一层存的是原始 JSON 字符串，Anthropic 要对象；
     * 解析失败给空对象而不是抛错，避免一条坏历史让整轮对话不可用。
     *
     * @param argumentsJson 参数字符串，可空
     * @return 参数对象
     */
    private JsonNode readArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(argumentsJson);
            return node != null && node.isObject() ? node : json.createObjectNode();
        } catch (Exception ex) {
            return json.createObjectNode();
        }
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
