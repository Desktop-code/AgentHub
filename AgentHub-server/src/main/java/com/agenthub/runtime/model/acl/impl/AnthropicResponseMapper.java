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
 * Anthropic Messages 响应 → UnifiedChatResponse 的映射器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 只处理非流式完整响应；流式走 {@link AnthropicStreamDecoder}。
 * 本类只做纯数据搬运，不做 IO、不持有状态，可并发使用。
 */
final class AnthropicResponseMapper {

    private final ObjectMapper json;

    /**
     * @param json 序列化器，复用容器配置
     */
    AnthropicResponseMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 映射非流式响应。content 是 block 数组：text 拼成正文，tool_use 转统一工具调用，
     * thinking 块本期不进正文（避免把思考当答案回显给用户）。
     *
     * @param body 响应 JSON 文本
     * @return 统一响应
     * @throws IllegalStateException 响应为空、结构不合法或上游返回 error
     */
    UnifiedChatResponse toResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("上游返回空响应");
        }
        try {
            JsonNode root = json.readTree(body);
            // 错误也可能是 200 + {"type":"error"}，这里显式拦一层
            if ("error".equals(textOf(root, "type"))) {
                throw new IllegalStateException("上游返回错误响应");
            }
            JsonNode content = root.get("content");
            if (content == null || !content.isArray()) {
                throw new IllegalStateException("上游响应缺少 content");
            }
            StringBuilder text = new StringBuilder();
            List<UnifiedToolCall> calls = new ArrayList<>();
            for (JsonNode block : content) {
                String blockType = textOf(block, "type");
                if ("text".equals(blockType)) {
                    String piece = textOf(block, "text");
                    if (piece != null) {
                        text.append(piece);
                    }
                } else if ("tool_use".equals(blockType)) {
                    calls.add(UnifiedToolCall.builder()
                            .id(textOf(block, "id"))
                            .name(textOf(block, "name"))
                            .arguments(argumentsText(block.get("input")))
                            .build());
                }
                // thinking / redacted_thinking 块：本期不透出，业务无需感知
            }
            UnifiedMessage message = UnifiedMessage.builder()
                    .role("assistant")
                    .content(text.length() == 0 ? null : text.toString())
                    .toolCalls(calls)
                    .build();
            String stopReason = textOf(root, "stop_reason");
            UnifiedFinishReason reason = stopReason == null
                    ? (calls.isEmpty() ? UnifiedFinishReason.STOP : UnifiedFinishReason.TOOL_CALLS)
                    : mapStopReason(stopReason);
            return UnifiedChatResponse.builder()
                    .message(message)
                    .toolCalls(calls)
                    .finishReason(reason)
                    .usage(readUsage(root.get("usage")))
                    .build();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("上游响应无法解析", ex);
        }
    }

    /**
     * 把 tool_use.input 对象序列化回统一层要求的原始 JSON 字符串。
     *
     * @param input 参数节点，可空
     * @return 参数 JSON 字符串；缺失时给 "{}"
     */
    private String argumentsText(JsonNode input) {
        if (input == null || input.isNull()) {
            return "{}";
        }
        return input.toString();
    }

    /**
     * 读取 usage。Anthropic 只给 input_tokens / output_tokens，total 由两者相加得出。
     *
     * @param node usage 节点，可空
     * @return 统一用量；全字段缺失时返回 null（不伪造 0）
     */
    private UnifiedUsage readUsage(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        Integer prompt = intOf(node, "input_tokens");
        Integer completion = intOf(node, "output_tokens");
        if (prompt == null && completion == null) {
            return null;
        }
        // 两项都齐时 total 就是真实总量，缺一项则保持 null 交由计费侧估算
        Integer total = (prompt != null && completion != null) ? prompt + completion : null;
        return UnifiedUsage.builder().prompt(prompt).completion(completion).total(total).build();
    }

    /**
     * stop_reason → 统一枚举。规则与流式解码器共用一套语义，避免两条路径结论不一致。
     *
     * @param raw 厂商原始 stop_reason
     * @return 统一结束原因，不会为 null
     */
    private UnifiedFinishReason mapStopReason(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "end_turn", "stop_sequence" -> UnifiedFinishReason.STOP;
            case "tool_use" -> UnifiedFinishReason.TOOL_CALLS;
            case "max_tokens" -> UnifiedFinishReason.LENGTH;
            case "refusal" -> UnifiedFinishReason.CONTENT_FILTER;
            case "pause_turn" -> UnifiedFinishReason.ERROR;
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
