package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容族响应 → UnifiedChatResponse 的映射器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 只处理非流式完整响应；流式走 {@link OpenAiStreamDecoder}。
 * 本类只做纯数据搬运，不做 IO、不持有状态，可并发使用。
 */
final class OpenAiResponseMapper {

    private final ObjectMapper json;

    /**
     * @param json 序列化器，复用容器配置
     */
    OpenAiResponseMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 非流式响应映射。
     *
     * @param body 响应 JSON 文本
     * @return 统一响应
     * @throws IllegalStateException 响应为空或结构不合法
     */
    UnifiedChatResponse toResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("上游返回空响应");
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("上游响应缺少 choices");
            }
            JsonNode choice = choices.get(0);
            JsonNode messageNode = choice.get("message");
            List<UnifiedToolCall> calls = readToolCallsFromMessage(messageNode);
            UnifiedMessage message = UnifiedMessage.builder()
                    .role("assistant")
                    .content(messageNode == null ? null : textOf(messageNode, "content"))
                    .toolCalls(calls)
                    .build();
            JsonNode usageNode = root.get("usage");
            UnifiedUsage usage = null;
            if (usageNode != null && !usageNode.isNull()) {
                usage = UnifiedUsage.builder()
                        .prompt(intOf(usageNode, "prompt_tokens"))
                        .completion(intOf(usageNode, "completion_tokens"))
                        .total(intOf(usageNode, "total_tokens"))
                        .build();
            }
            String finish = textOf(choice, "finish_reason");
            // 上游偶尔不给 finish_reason：有工具调用就按 tool_calls，否则按 stop，绝不留 null
            UnifiedFinishReason reason = finish == null
                    ? (calls.isEmpty() ? UnifiedFinishReason.STOP : UnifiedFinishReason.TOOL_CALLS)
                    : mapFinishReason(finish);
            return UnifiedChatResponse.builder()
                    .message(message)
                    .toolCalls(calls)
                    .finishReason(reason)
                    .usage(usage)
                    .build();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("上游响应无法解析", ex);
        }
    }

    /**
     * 非流式响应里的工具调用是完整对象，直接读取即可，无需拼装。
     *
     * @param messageNode 响应 message 节点，可空
     * @return 工具调用列表；无调用时为空列表
     */
    private List<UnifiedToolCall> readToolCallsFromMessage(JsonNode messageNode) {
        List<UnifiedToolCall> calls = new ArrayList<>();
        if (messageNode == null) {
            return calls;
        }
        JsonNode array = messageNode.get("tool_calls");
        if (array == null || !array.isArray()) {
            return calls;
        }
        for (JsonNode item : array) {
            JsonNode function = item.get("function");
            calls.add(UnifiedToolCall.builder()
                    .id(textOf(item, "id"))
                    .name(function == null ? null : textOf(function, "name"))
                    .arguments(function == null ? "{}" : textOf(function, "arguments"))
                    .build());
        }
        return calls;
    }

    /**
     * 结束原因映射。非流式与流式共用同一套规则，未知取值按 stop 处理，
     * 避免新增厂商文案把正常回复误判成错误。
     *
     * @param raw 厂商原始结束标识
     * @return 统一结束原因，不会为 null
     */
    private UnifiedFinishReason mapFinishReason(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "length", "max_tokens" -> UnifiedFinishReason.LENGTH;
            case "content_filter", "safety" -> UnifiedFinishReason.CONTENT_FILTER;
            case "tool_calls", "function_call", "tool_use" -> UnifiedFinishReason.TOOL_CALLS;
            case "error", "server_error", "insufficient_system_resource" -> UnifiedFinishReason.ERROR;
            default -> UnifiedFinishReason.STOP;
        };
    }

    /**
     * @return 文本字段；缺失或 JSON null 返回 null
     */
    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * @return 整数字段；缺失或非数字返回 null（不补 0，让计费侧自行判断缺失）
     */
    private static Integer intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || !value.isNumber()) ? null : value.asInt();
    }
}
