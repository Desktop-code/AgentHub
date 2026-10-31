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
 * DashScope 原生接口响应 → UnifiedChatResponse 的映射器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 只处理非流式完整响应；流式走 {@link DashScopeStreamDecoder}。
 * 本类只做纯数据搬运，不做 IO、不持有状态，可并发使用。
 */
final class DashScopeResponseMapper {

    private final ObjectMapper json;

    /**
     * @param json 序列化器，复用容器配置
     */
    DashScopeResponseMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 映射非流式响应。原生结构是 {@code output.choices[0].message}，
     * usage 字段用 input_tokens / output_tokens（部分模型仍是 prompt_tokens / completion_tokens）。
     *
     * @param body 响应 JSON 文本
     * @return 统一响应
     * @throws IllegalStateException 响应为空、结构不合法或上游返回了错误码
     */
    UnifiedChatResponse toResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("上游返回空响应");
        }
        try {
            JsonNode root = json.readTree(body);
            // 原生接口错误也走 HTTP 200：code 非空且不是 200 即失败
            String code = textOf(root, "code");
            if (code != null && !code.isBlank() && !"200".equals(code)) {
                throw new IllegalStateException("上游返回错误码 " + code);
            }
            JsonNode output = root.get("output");
            JsonNode choices = output == null ? null : output.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("上游响应缺少 output.choices");
            }
            JsonNode choice = choices.get(0);
            JsonNode messageNode = choice.get("message");
            List<UnifiedToolCall> calls = readToolCallsFromMessage(messageNode);
            UnifiedMessage message = UnifiedMessage.builder()
                    .role("assistant")
                    .content(messageNode == null ? null : textOf(messageNode, "content"))
                    .toolCalls(calls)
                    .build();
            UnifiedUsage usage = readUsage(root.get("usage"));
            String finish = textOf(choice, "finish_reason");
            if (finish == null) {
                finish = textOf(output, "finish_reason");
            }
            // 上游偶尔不给 finish_reason：有工具调用就按 tool_calls，否则按 stop，绝不留 null
            UnifiedFinishReason reason = (finish == null || finish.isBlank() || "null".equals(finish))
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
     * 非流式响应里的工具调用是完整对象，直接读取即可。
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
     * 读取 usage。原生用 input_tokens / output_tokens，兼容模式用 prompt_tokens / completion_tokens。
     *
     * @param node usage 节点，可空
     * @return 统一用量；全字段缺失时返回 null（不伪造 0）
     */
    private UnifiedUsage readUsage(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        Integer prompt = intOf(node, "input_tokens");
        if (prompt == null) {
            prompt = intOf(node, "prompt_tokens");
        }
        Integer completion = intOf(node, "output_tokens");
        if (completion == null) {
            completion = intOf(node, "completion_tokens");
        }
        Integer total = intOf(node, "total_tokens");
        if (prompt == null && completion == null && total == null) {
            return null;
        }
        return UnifiedUsage.builder().prompt(prompt).completion(completion).total(total).build();
    }

    /**
     * 结束原因映射。原生用字符串 "null" 表示「还没结束」，这里按自然结束处理。
     *
     * @param raw 厂商原始结束标识
     * @return 统一结束原因
     */
    private UnifiedFinishReason mapFinishReason(String raw) {
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "stop", "end_turn", "eos", "null" -> UnifiedFinishReason.STOP;
            case "tool_calls", "function_call", "tool_use" -> UnifiedFinishReason.TOOL_CALLS;
            case "length", "max_tokens" -> UnifiedFinishReason.LENGTH;
            case "content_filter", "safety", "data_inspection_failed" -> UnifiedFinishReason.CONTENT_FILTER;
            case "error", "server_error" -> UnifiedFinishReason.ERROR;
            default -> UnifiedFinishReason.STOP;
        };
    }

    /**
     * @return 文本字段值；缺失或 JSON null 返回 null
     */
    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * @return 整数字段值；缺失或非数字返回 null
     */
    private static Integer intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || !value.isNumber()) ? null : value.asInt();
    }
}
