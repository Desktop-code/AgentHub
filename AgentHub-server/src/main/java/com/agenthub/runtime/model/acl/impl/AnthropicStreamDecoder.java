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
 * Anthropic Messages 流式事件 → UnifiedStreamEvent 的状态机。
 * 所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么需要它：Anthropic 的 SSE 是<b>结构化事件流</b>而不是「每帧一个 delta 对象」，差异集中在四点：
 * <ol>
 *   <li>帧级 {@code event:} 名有语义（content_block_start / content_block_delta / message_delta …），
 *       delta 的类型又在 {@code delta.type} 里，两层都要看；</li>
 *   <li>思考是独立 block（{@code content_block.type=thinking} + {@code thinking_delta.thinking}），
 *       与 OpenAI 的 reasoning_content 字段完全不是一个形状；</li>
 *   <li>工具调用是 {@code tool_use} block：首帧给 id 与 name，参数则是一串
 *       {@code input_json_delta.partial_json} 文本碎片，需要按 block index 拼接；</li>
 *   <li>结束是 {@code message_delta.stop_reason} + {@code message_stop}，没有 {@code [DONE]}。</li>
 * </ol>
 * 由于 {@link SsePayloads} 只保留 data 行（丢弃 event: 行），本类一律以 data 里的 {@code type}
 * 字段为准判断帧类型——Anthropic 的 data JSON 本身带 type，两条信息等价。
 * 线程约束：非线程安全。一个流实例新建一个，只在该流的 Reactor 订阅链上顺序调用
 * （调用方必须用 concatMap 保证顺序，禁止 flatMap）。
 */
final class AnthropicStreamDecoder implements VendorStreamDecoder {

    private final ObjectMapper json;
    private final ToolCallAccumulator tools = new ToolCallAccumulator();
    /** 已发过 completed 的工具调用下标（即 content block index） */
    private final Set<Integer> completedIndexes = new HashSet<>();

    private UnifiedUsage usage;
    private UnifiedFinishReason finishReason;
    /** 上游原始 stop_reason 文本，仅用于诊断扩展字段 */
    private String rawStopReason;
    private boolean usageEmitted;
    /** 已收到 message_stop，用于区分「正常收尾」与「缺结束标识的截断」 */
    private boolean messageStopped;

    /**
     * @param json 复用容器内的 ObjectMapper 配置；本类不做任何 IO
     */
    AnthropicStreamDecoder(ObjectMapper json) {
        this.json = json;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<UnifiedStreamEvent> onPayload(String payload) {
        List<UnifiedStreamEvent> events = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return events;
        }
        JsonNode root = readTree(payload);
        if (root == null) {
            // ping 等非业务帧：跳过，done 由收尾统一兜底
            return events;
        }
        String type = textOf(root, "type");
        if (type == null) {
            return events;
        }
        switch (type) {
            case "message_start" -> readMessageStart(root);
            case "content_block_start" -> readContentBlockStart(root, events);
            case "content_block_delta" -> readContentBlockDelta(root, events);
            case "content_block_stop" -> readContentBlockStop(root, events);
            case "message_delta" -> readMessageDelta(root);
            case "message_stop" -> messageStopped = true;
            case "error" -> readError(root, events);
            default -> {
                // 未知帧类型（如未来新增的扩展事件）：忽略不报错，符合「未知类型忽略」约定
            }
        }
        emitUsageIfPresent(events);
        return events;
    }

    /**
     * {@inheritDoc}
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
     * {@inheritDoc}
     */
    @Override
    public UnifiedUsage usage() {
        return usage;
    }

    /**
     * {@inheritDoc}
     * 结束原因优先级：上游 stop_reason > 未收到 message_stop（视为截断，按 LENGTH 提示）> 有待执行工具调用 > 自然结束。
     * 注意「截断」在这里不给 ERROR：数据是可用的，只是不完整，用 LENGTH 让前端提示更准确。
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
     * @return 最终结束原因，保证非 null
     */
    private UnifiedFinishReason resolveFinishReason() {
        if (finishReason != null) {
            return finishReason;
        }
        if (tools.hasPending()) {
            return UnifiedFinishReason.TOOL_CALLS;
        }
        if (!messageStopped) {
            // 上游既没给 stop_reason 也没给 message_stop：连接被截断，数据可用但不完整
            return UnifiedFinishReason.LENGTH;
        }
        return UnifiedFinishReason.STOP;
    }

    /**
     * @return 诊断用扩展字段；上游给了 stop_reason 时带上原文
     */
    private Map<String, Object> diagnostics() {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (rawStopReason != null) {
            extra.put("vendor.stop_reason", rawStopReason);
        }
        if (!messageStopped) {
            extra.put("vendor.message_stop_missing", true);
        }
        return extra;
    }

    /**
     * message_start：携带 message.id 与首份 usage（input_tokens 在这里，output_tokens 后续才更新）。
     */
    private void readMessageStart(JsonNode root) {
        JsonNode message = root.get("message");
        if (message == null) {
            return;
        }
        JsonNode usageNode = message.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            mergeUsage(usageNode);
        }
    }

    /**
     * content_block_start：新开一个块。按 type 决定是 text / thinking / tool_use。
     * tool_use 块的首帧带 id 与 name，参数随后由 input_json_delta 逐段拼。
     */
    private void readContentBlockStart(JsonNode root, List<UnifiedStreamEvent> events) {
        int index = intValue(root, "index", 0);
        JsonNode block = root.get("content_block");
        if (block == null) {
            return;
        }
        String blockType = textOf(block, "type");
        if ("thinking".equals(blockType)) {
            // 首帧可能给一段初始思考文本（多数为空串）
            String initial = textOf(block, "thinking");
            if (initial != null && !initial.isEmpty()) {
                events.add(UnifiedStreamEvent.thinkingDelta(initial));
            }
            return;
        }
        if ("tool_use".equals(blockType)) {
            String id = textOf(block, "id");
            String name = textOf(block, "name");
            // 首帧就把 id/name 交给累加器，后续 input_json_delta 只追加参数
            ToolCallAccumulator.PendingCall call = tools.accept(index, id, name, null);
            events.add(UnifiedStreamEvent.builder()
                    .type(UnifiedStreamEventType.TOOL_CALL_DELTA)
                    .toolCallDelta(UnifiedToolCall.builder()
                            .id(call.id())
                            .name(call.name())
                            .arguments(null)
                            .build())
                    .build());
            return;
        }
        // text 块：正文片段都在后续 content_block_delta 里，这里不产出事件
        String initialText = textOf(block, "text");
        if (initialText != null && !initialText.isEmpty()) {
            events.add(UnifiedStreamEvent.messageDelta(initialText));
        }
    }

    /**
     * content_block_delta：按 delta.type 分派。
     * text_delta → 正文；thinking_delta → 思考；input_json_delta → 工具参数碎片；signature_delta → 忽略。
     */
    private void readContentBlockDelta(JsonNode root, List<UnifiedStreamEvent> events) {
        JsonNode delta = root.get("delta");
        if (delta == null) {
            return;
        }
        String deltaType = textOf(delta, "type");
        if (deltaType == null) {
            return;
        }
        switch (deltaType) {
            case "text_delta" -> {
                String text = textOf(delta, "text");
                if (text != null && !text.isEmpty()) {
                    events.add(UnifiedStreamEvent.messageDelta(text));
                }
            }
            case "thinking_delta" -> {
                String thinking = textOf(delta, "thinking");
                if (thinking != null && !thinking.isEmpty()) {
                    events.add(UnifiedStreamEvent.thinkingDelta(thinking));
                }
            }
            case "input_json_delta" -> {
                String partial = textOf(delta, "partial_json");
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                int index = intValue(root, "index", 0);
                // 参数碎片只追加；同时发一条 delta 让「工具调用参数增量」可观测（定稿 §6 要求），
                // 但真正可执行的是后面 completed 里的完整 JSON——delta 只是进度，可能被切成半截键名
                ToolCallAccumulator.PendingCall call = tools.accept(index, null, null, partial);
                events.add(UnifiedStreamEvent.builder()
                        .type(UnifiedStreamEventType.TOOL_CALL_DELTA)
                        .toolCallDelta(UnifiedToolCall.builder()
                                .id(call.id())
                                .name(call.name())
                                .arguments(partial)
                                .build())
                        .build());
            }
            default -> {
                // signature_delta / citations_delta 等：本期不消费，忽略不报错
            }
        }
    }

    /**
     * content_block_stop：普通块关闭。若关闭的是 tool_use 块，参数此刻已拼完，可发 completed。
     */
    private void readContentBlockStop(JsonNode root, List<UnifiedStreamEvent> events) {
        int index = intValue(root, "index", 0);
        // 用累加器快照判断该下标是否真的是工具调用块：文本块的 stop 不应产出 completed
        for (Map.Entry<Integer, UnifiedToolCall> entry : tools.completeEntries()) {
            if (entry.getKey() == index && completedIndexes.add(index)) {
                events.add(UnifiedStreamEvent.builder()
                        .type(UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                        .toolCallDelta(entry.getValue())
                        .build());
            }
        }
    }

    /**
     * message_delta：携带 stop_reason 与最终 output_tokens。
     */
    private void readMessageDelta(JsonNode root) {
        JsonNode delta = root.get("delta");
        if (delta != null) {
            String stop = textOf(delta, "stop_reason");
            if (stop != null && !stop.isBlank()) {
                rawStopReason = stop;
                finishReason = mapStopReason(stop);
            }
        }
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            mergeUsage(usageNode);
        }
    }

    /**
     * error 帧：转成统一 error 事件，但不在这里发 done（收尾统一由装配器兜底）。
     * Anthropic 的 error.message 是脱敏后的说明，可直接透出；不拼原始请求体。
     */
    private void readError(JsonNode root, List<UnifiedStreamEvent> events) {
        JsonNode error = root.get("error");
        String message = error == null ? null : textOf(error, "message");
        events.add(UnifiedStreamEvent.error(
                message == null || message.isBlank() ? "模型服务返回错误" : message,
                UnifiedFinishReason.ERROR));
        finishReason = UnifiedFinishReason.ERROR;
    }

    /**
     * 合并 usage。message_start 给 input_tokens，message_delta 给递增后的 output_tokens，
     * 两次都要吸收，否则最终 done 会缺一半数据。
     */
    private void mergeUsage(JsonNode node) {
        Integer input = intOf(node, "input_tokens");
        Integer output = intOf(node, "output_tokens");
        Integer promptValue = input != null ? input : (usage == null ? null : usage.getPrompt());
        Integer completionValue = output != null ? output : (usage == null ? null : usage.getCompletion());
        if (promptValue == null && completionValue == null) {
            return;
        }
        // Anthropic 不给 total；这里如实做加总，因为两项都齐时它等于真实总用量
        Integer total = (promptValue != null && completionValue != null) ? promptValue + completionValue : null;
        usage = UnifiedUsage.builder().prompt(promptValue).completion(completionValue).total(total).build();
    }

    /**
     * 用量事件只发一次：首次拿到 output_tokens（即流尾部）时发出。
     */
    private void emitUsageIfPresent(List<UnifiedStreamEvent> events) {
        if (usage == null || usageEmitted || usage.getCompletion() == null) {
            return;
        }
        usageEmitted = true;
        events.add(UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.USAGE)
                .usage(usage)
                .build());
    }

    /**
     * Anthropic stop_reason → 统一枚举。
     * end_turn / stop_sequence 是正常结束；tool_use 要执行工具；max_tokens 是截断；
     * refusal 是模型拒绝输出（按内容拦截处理）；pause_turn 属服务端暂停，按错误提示更安全。
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
     * 容错解析。脏帧返回 null 由调用方跳过。
     */
    private JsonNode readTree(String payload) {
        String text = payload.trim();
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

    /**
     * @return 整数字段值；缺失时返回默认值
     */
    private static int intValue(JsonNode node, String field, int defaultValue) {
        Integer value = intOf(node, field);
        return value == null ? defaultValue : value;
    }
}
