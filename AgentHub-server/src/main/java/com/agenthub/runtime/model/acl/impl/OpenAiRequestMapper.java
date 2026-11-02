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
 * OpenAI 兼容族请求体映射器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 覆盖 DeepSeek / GLM / Ollama / vLLM 与多数中转的 {@code POST /chat/completions} 结构。
 * 本类只做纯数据搬运，不做 IO、不持有状态，可并发使用。
 */
final class OpenAiRequestMapper {

    private final ObjectMapper json;

    /**
     * @param json 序列化器，复用容器配置
     */
    OpenAiRequestMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 组装请求体。stream 决定是否逐字返回。
     * tools 为空时按规范省略整个字段——部分厂商对空数组会直接返回 400。
     *
     * @param request 统一请求
     * @param model   模型实例 id
     * @param stream  是否流式
     * @return JSON 文本
     */
    String build(UnifiedChatRequest request, String model, boolean stream) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("stream", stream);
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            root.put("max_tokens", request.getMaxTokens());
        }
        ArrayNode messages = root.putArray("messages");
        if (request.getMessages() != null) {
            for (UnifiedMessage message : request.getMessages()) {
                messages.add(toMessageNode(message));
            }
        }
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (UnifiedToolDef tool : request.getTools()) {
                ObjectNode item = tools.addObject();
                item.put("type", "function");
                ObjectNode function = item.putObject("function");
                function.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    function.put("description", tool.getDescription());
                }
                // schema 存的是字符串，需解析成对象再内联；非法 JSON 属于装配错误，直接暴露
                function.set("parameters", readSchema(tool.getParametersJsonSchema()));
            }
        }
        return root.toString();
    }

    /**
     * 把统一消息映射成 OpenAI 消息对象。tool 角色必须带 tool_call_id；
     * assistant 历史里的工具调用要原样带回，否则多轮工具对话会丢上下文。
     *
     * @param message 统一消息
     * @return 厂商消息对象
     */
    private ObjectNode toMessageNode(UnifiedMessage message) {
        ObjectNode node = json.createObjectNode();
        node.put("role", message.getRole());
        // 图文混排：有图时 content 必须是 [{type:text},{type:image_url}] 数组，
        // 纯文本时仍下发字符串——部分兼容实现（Ollama / 老版 vLLM）对分段数组支持不全，
        // 没有图片也发数组会平白多出一类 400。
        if (message.hasImages()) {
            node.set("content", multimodalContent(message));
        } else if (message.getContent() != null) {
            node.put("content", message.getContent());
        }
        if (message.getToolCallId() != null && !message.getToolCallId().isBlank()) {
            node.put("tool_call_id", message.getToolCallId());
        }
        if (message.getName() != null && !message.getName().isBlank()) {
            node.put("name", message.getName());
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
     * 组装多模态 content 数组。形状按 OpenAI 视觉消息规范：
     * <pre>
     * [
     *   {"type": "text", "text": "用户的文字提问"},
     *   {"type": "image_url", "image_url": {"url": "图片的URL或data URI"}}
     * ]
     * </pre>
     * 约定：
     * 1. 文字段恒排第一段。纯发图（content 为空）时仍补一段空文本，
     *    部分厂商缺少 text 段会直接 400，且空数组同样非法；
     * 2. 图片段的 url 原样透传，data URI 与 http(s) 地址都由上游自行拉取或解码；
     * 3. 空白图片项跳过而不是发空串，避免整条消息因一张坏图被拒。
     *
     * @param message 统一消息，调用前已确认 {@link UnifiedMessage#hasImages()}
     * @return 可直接内联到 messages[].content 的数组节点
     */
    private ArrayNode multimodalContent(UnifiedMessage message) {
        ArrayNode parts = json.createArrayNode();
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", message.getContent() == null ? "" : message.getContent());
        for (String imageUrl : message.getImageUrls()) {
            if (imageUrl == null || imageUrl.isBlank()) {
                continue;
            }
            ObjectNode imagePart = parts.addObject();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url").put("url", imageUrl.trim());
        }
        return parts;
    }

    /**
     * 解析工具参数 schema 字符串。空值给一个宽松的 object schema，避免上游 400。
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
