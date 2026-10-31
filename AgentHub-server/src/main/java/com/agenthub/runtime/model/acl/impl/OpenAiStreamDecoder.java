package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容流式 chunk → UnifiedStreamEvent 的状态机。所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么单独成类：碎片拼装与结束原因推导是整条流最容易出错的地方，抽出来可以脱离 HTTP 单测，
 * 也避免把这段逻辑埋进 WebClient 链里没人看得清。
 * 状态：tool_call 累加、已完成的工具调用下标、已见 usage、已见 finish_reason。
 * 线程约束：非线程安全。一个流实例新建一个，只在该流的 Reactor 订阅链上顺序调用
 * （调用方必须用 concatMap 保证顺序，禁止 flatMap）。
 */
final class OpenAiStreamDecoder implements VendorStreamDecoder {

    private final ObjectMapper json;
    private final ToolCallAccumulator tools = new ToolCallAccumulator();

    /** 已发过 completed 的工具调用 index，防止 finish 与流末尾兜底重复发 */
    private final Set<Integer> completedIndexes = new HashSet<>();

    private UnifiedUsage usage;
    /** 上游给出的结束原因，可能一直为 null（部分中转不发 finish_reason 直接断开） */
    private UnifiedFinishReason finishReason;
    /** 上游原始 finish_reason 文本，仅用于诊断扩展字段 */
    private String rawFinishReason;
    /** usage 事件是否已发出，保证整条流最多一个 usage 事件 */
    private boolean usageEmitted;

    /**
     * @param json 复用容器内的 ObjectMapper 配置；本类不做任何 IO
     */
    OpenAiStreamDecoder(ObjectMapper json) {
        this.json = json;
    }

    /**
     * {@inheritDoc}
     * 一帧可能同时带思考、正文与工具调用分片，故返回列表。
     */
    @Override
    public List<UnifiedStreamEvent> onPayload(String payload) {
        List<UnifiedStreamEvent> events = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return events;
        }
        JsonNode root = readTree(payload);
        if (root == null) {
            // 上游偶发心跳或非 JSON 文本：不当作错误，直接跳过，done 由收尾统一兜底
            return events;
        }
        // usage 可能出现在任意帧（OpenAI 的 include_usage 通常在最后带 usage 的独立帧）
        readUsage(root.get("usage"));
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            // 只有 usage 的帧也属于正常情况，此处仅把 usage 事件补出去
            emitUsageIfPresent(events);
            return events;
        }
        for (JsonNode choice : choices) {
            JsonNode delta = choice.get("delta");
            if (delta != null) {
                readThinking(delta, events);
                readContent(delta, events);
                readToolCalls(delta, events);
            }
            JsonNode finish = choice.get("finish_reason");
            if (finish != null && !finish.isNull()) {
                applyFinishReason(finish.asText(), events);
            }
        }
        emitUsageIfPresent(events);
        return events;
    }

    /**
     * {@inheritDoc}
     * 收尾：把还没发过 completed 的工具调用补发出去。参数已拼完整，可安全执行。
     * 去重按 <b>累加器归组下标</b> 而不是发射顺序：finish_reason 帧可能只覆盖部分调用，
     * 用顺序号去重会误判，导致真实调用被漏发。
     */
    @Override
    public List<UnifiedStreamEvent> drainCompleted() {
        List<UnifiedStreamEvent> events = new ArrayList<>();
        for (Map.Entry<Integer, UnifiedToolCall> entry : tools.completeEntries()) {
            if (!completedIndexes.add(entry.getKey())) {
                continue;
            }
            events.add(UnifiedStreamEvent.builder()
                    .type(UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                    .toolCallDelta(entry.getValue())
                    .build());
        }
        return events;
    }

    /**
     * 推导最终结束原因。优先级：上游明确给出的 finish_reason > 有待执行工具调用 > 自然结束。
     * 上游缺标识时绝不能返回 null，否则前端无法收尾。
     *
     * @return 结束原因
     */
    UnifiedFinishReason resolveFinishReason() {
        if (finishReason != null) {
            return finishReason;
        }
        // 没有 finish_reason 但有完整工具调用：按「模型要调工具」收尾，让 ReAct 能继续
        if (tools.hasPending()) {
            return UnifiedFinishReason.TOOL_CALLS;
        }
        return UnifiedFinishReason.STOP;
    }

    /**
     * {@inheritDoc}
     * 带诊断扩展（上游原始 finish_reason），便于排查「厂商回了非常规结束文案」这类问题，
     * 业务侧默认忽略未知键。
     */
    @Override
    public UnifiedStreamEvent doneEvent() {
        return UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.DONE)
                .finishReason(resolveFinishReason())
                .usage(usage)
                .extensions(diagnostics())
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UnifiedUsage usage() {
        return usage;
    }

    /**
     * @return 诊断用扩展字段；上游给了非常规 finish_reason 时带上原文
     */
    Map<String, Object> diagnostics() {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (rawFinishReason != null) {
            extra.put("vendor.finish_reason", rawFinishReason);
        }
        return extra;
    }

    /**
     * 读取思考增量。DeepSeek 用 reasoning_content，部分兼容实现用 reasoning。
     */
    private void readThinking(JsonNode delta, List<UnifiedStreamEvent> events) {
        String thinking = textOf(delta, "reasoning_content");
        if (thinking == null) {
            thinking = textOf(delta, "reasoning");
        }
        if (thinking != null && !thinking.isEmpty()) {
            events.add(UnifiedStreamEvent.thinkingDelta(thinking));
        }
    }

    /**
     * 读取正文增量。content 可能是 null（纯工具调用帧）。
     */
    private void readContent(JsonNode delta, List<UnifiedStreamEvent> events) {
        String content = textOf(delta, "content");
        if (content != null && !content.isEmpty()) {
            events.add(UnifiedStreamEvent.messageDelta(content));
        }
    }

    /**
     * 读取工具调用分片。按 index 归组，arguments 逐段拼接。
     */
    private void readToolCalls(JsonNode delta, List<UnifiedStreamEvent> events) {
        JsonNode calls = delta.get("tool_calls");
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (JsonNode item : calls) {
            Integer index = item.hasNonNull("index") ? item.get("index").asInt() : null;
            String id = textOf(item, "id");
            JsonNode function = item.get("function");
            String name = function == null ? null : textOf(function, "name");
            String arguments = function == null ? null : textOf(function, "arguments");
            // 先入累加器再发 delta：delta 只是可观测的进度，completed 才是可执行参数
            ToolCallAccumulator.PendingCall call = tools.accept(index, id, name, arguments);
            events.add(UnifiedStreamEvent.builder()
                    .type(UnifiedStreamEventType.TOOL_CALL_DELTA)
                    .toolCallDelta(UnifiedToolCall.builder()
                            .id(call.id())
                            .name(call.name())
                            .arguments(arguments)
                            .build())
                    .build());
        }
    }

    /**
     * 记录结束原因；若模型明确要调工具，立即把已拼完整的调用发出去，让上游不用等到流末尾。
     */
    private void applyFinishReason(String raw, List<UnifiedStreamEvent> events) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        rawFinishReason = raw;
        finishReason = mapFinishReason(raw);
        // 明确 tool_calls 时立刻补 completed，前端可以马上开始渲染工具卡片
        if (finishReason == UnifiedFinishReason.TOOL_CALLS) {
            events.addAll(drainCompleted());
        }
    }

    /**
     * 厂商 finish_reason 文本 → 统一枚举。未知取值按「自然结束」处理并保留原文，
     * 避免新增厂商文案把正常回复误判成错误。
     */
    private UnifiedFinishReason mapFinishReason(String raw) {
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "stop", "end_turn", "eos" -> UnifiedFinishReason.STOP;
            case "tool_calls", "function_call", "tool_use" -> UnifiedFinishReason.TOOL_CALLS;
            case "length", "max_tokens" -> UnifiedFinishReason.LENGTH;
            case "content_filter", "safety" -> UnifiedFinishReason.CONTENT_FILTER;
            case "error", "server_error", "insufficient_system_resource" -> UnifiedFinishReason.ERROR;
            default -> UnifiedFinishReason.STOP;
        };
    }

    /**
     * 读取 usage 节点。字段缺失不补 0，保持 null 让计费侧自行判断。
     */
    private void readUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        Integer prompt = intOf(node, "prompt_tokens");
        Integer completion = intOf(node, "completion_tokens");
        Integer total = intOf(node, "total_tokens");
        if (prompt == null && completion == null && total == null) {
            return;
        }
        usage = UnifiedUsage.builder()
                .prompt(prompt)
                .completion(completion)
                .total(total)
                .build();
    }

    /**
     * 用量事件只发一次：拿到 usage 的当下发出，后续帧不重复发，避免前端累计出错。
     */
    private void emitUsageIfPresent(List<UnifiedStreamEvent> events) {
        if (usage == null || usageEmitted) {
            return;
        }
        usageEmitted = true;
        events.add(UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.USAGE)
                .usage(usage)
                .build());
    }

    /**
     * 容错解析。脏帧返回 null 由调用方跳过。
     */
    private JsonNode readTree(String payload) {
        String text = payload.trim();
        // 少数网关不透传 SSE 语义，会把 "data: " 前缀留在文本里，这里兜一层
        if (text.startsWith("data:")) {
            text = text.substring("data:".length()).trim();
        }
        if (text.isEmpty() || SsePayloads.DONE_MARKER.equals(text)) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (Exception ex) {
            return null;
        }
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
