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
 * DashScope 原生协议流式 chunk → UnifiedStreamEvent 的状态机。
 * 所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么需要它：灵积原生接口与 OpenAI 兼容族的差别不只是路径——
 * <ol>
 *   <li>没有 {@code [DONE]} 标记，靠 {@code output.finish_reason} 或 HTTP EOF 收尾；</li>
 *   <li>正文在 {@code output.choices[].message.content}，并且原生流是 <b>增量</b>（每帧只带新增片段）；</li>
 *   <li>思考在新版模型的 {@code output.choices[].message.reasoning_content}；</li>
 *   <li>工具调用在 {@code output.choices[].message.tool_calls}，分片形态与 OpenAI 近似但不带 index，
 *       需按 id 归组（同一调用首帧给 id，后续帧 id 可能为空）。</li>
 * </ol>
 * 线程约束：非线程安全。一个流实例新建一个，只在该流的 Reactor 订阅链上顺序调用
 * （调用方必须用 concatMap 保证顺序，禁止 flatMap）。
 */
final class DashScopeStreamDecoder implements VendorStreamDecoder {

    private final ObjectMapper json;
    private final ToolCallAccumulator tools = new ToolCallAccumulator();
    /** 已发过 completed 的工具调用下标，防止 finish 与流末尾兜底重复发 */
    private final Set<Integer> completedIndexes = new HashSet<>();

    private UnifiedUsage usage;
    /** 上游给出的结束原因，可能一直为 null（原生接口不发 finish_reason 直接 EOF） */
    private UnifiedFinishReason finishReason;
    /** 上游原始 finish_reason 文本，仅用于诊断扩展字段 */
    private String rawFinishReason;
    /** usage 事件是否已发出，保证整条流最多一个 usage 事件 */
    private boolean usageEmitted;
    /**
     * 工具调用 id → 累加器归组下标。原生接口多数帧不带 index，
     * 只能靠 id 判断「这条碎片属于哪个调用」。
     */
    private final Map<String, Integer> idToIndex = new LinkedHashMap<>();

    /**
     * @param json 复用容器内的 ObjectMapper 配置；本类不做任何 IO
     */
    DashScopeStreamDecoder(ObjectMapper json) {
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
            // 非 JSON 心跳：不当作错误，直接跳过，done 由收尾统一兜底
            return events;
        }
        readUsage(root.get("usage"));
        JsonNode output = root.get("output");
        if (output != null && output.isObject()) {
            JsonNode choices = output.get("choices");
            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode message = choice.get("message");
                    if (message != null) {
                        readThinking(message, events);
                        readContent(message, events);
                        readToolCalls(message, events);
                    }
                    // 原生接口把 finish_reason 同时挂在 choice 与 output 上，两级都要看
                    JsonNode choiceFinish = choice.get("finish_reason");
                    if (choiceFinish != null && !choiceFinish.isNull()) {
                        applyFinishReason(choiceFinish.asText(), events);
                    }
                }
            }
            JsonNode outputFinish = output.get("finish_reason");
            if (outputFinish != null && !outputFinish.isNull()) {
                applyFinishReason(outputFinish.asText(), events);
            }
        }
        // 上游明确的错误码：转成 error 事件但仍不在这里发 done，收尾交给装配器统一兜底
        String code = textOf(root, "code");
        if (code != null && !code.isBlank() && !"200".equals(code)) {
            String message = textOf(root, "message");
            events.add(UnifiedStreamEvent.error(
                    message == null || message.isBlank() ? "模型服务返回错误 " + code : message,
                    UnifiedFinishReason.ERROR));
            finishReason = UnifiedFinishReason.ERROR;
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
     * 推导最终结束原因。优先级：上游明确给出的 finish_reason > 有待执行工具调用 > 自然结束。
     * 上游缺标识时绝不能返回 null，否则前端无法收尾。
     *
     * @return 结束原因
     */
    private UnifiedFinishReason resolveFinishReason() {
        if (finishReason != null) {
            return finishReason;
        }
        // 没有 finish_reason 但有工具调用片段：按「模型要调工具」收尾，让 ReAct 能继续
        if (tools.hasPending()) {
            return UnifiedFinishReason.TOOL_CALLS;
        }
        return UnifiedFinishReason.STOP;
    }

    /**
     * @return 诊断用扩展字段；上游给了非常规 finish_reason 时带上原文
     */
    private Map<String, Object> diagnostics() {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (rawFinishReason != null) {
            extra.put("vendor.finish_reason", rawFinishReason);
        }
        return extra;
    }

    /**
     * 读取思考增量。灵积新版推理模型用 reasoning_content。
     */
    private void readThinking(JsonNode message, List<UnifiedStreamEvent> events) {
        String thinking = textOf(message, "reasoning_content");
        if (thinking != null && !thinking.isEmpty()) {
            events.add(UnifiedStreamEvent.thinkingDelta(thinking));
        }
    }

    /**
     * 读取正文增量。原生流 content 是新增片段；纯工具调用帧没有 content。
     */
    private void readContent(JsonNode message, List<UnifiedStreamEvent> events) {
        String content = textOf(message, "content");
        if (content != null && !content.isEmpty()) {
            events.add(UnifiedStreamEvent.messageDelta(content));
        }
    }

    /**
     * 读取工具调用分片。原生接口不带 index，按 id 归组；同一调用后续帧 id 为空时沿用上次下标。
     */
    private void readToolCalls(JsonNode message, List<UnifiedStreamEvent> events) {
        JsonNode calls = message.get("tool_calls");
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (JsonNode item : calls) {
            String id = textOf(item, "id");
            JsonNode function = item.get("function");
            String name = function == null ? null : textOf(function, "name");
            String arguments = function == null ? null : textOf(function, "arguments");
            Integer index = indexOfCall(id);
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
     * 按 id 解析归组下标。首次见到的 id 占一个新下标（取当前已知数量），
     * id 为空的帧沿用最后出现的调用。
     *
     * @param id 本帧携带的调用 id，可空
     * @return 归组下标；不会有 null
     */
    private Integer indexOfCall(String id) {
        if (id == null || id.isBlank()) {
            // 无 id 即认为是同一调用的后续碎片：传 null 让累加器沿用最后一个下标
            return null;
        }
        Integer known = idToIndex.get(id);
        if (known != null) {
            return known;
        }
        // 新 id 占一个新下标。不用 computeIfAbsent：映射函数里读 size() 属于边改边读，语义脆弱
        Integer index = idToIndex.size();
        idToIndex.put(id, index);
        return index;
    }

    /**
     * 记录结束原因；若模型明确要调工具，立即把已拼完整的调用发出去。
     * 原生接口用字符串 "null" 表示未完，此时不写入诊断字段，避免噪音。
     */
    private void applyFinishReason(String raw, List<UnifiedStreamEvent> events) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        UnifiedFinishReason mapped = mapFinishReason(raw);
        if (mapped == null) {
            // "null" 等未完标记：保持原状态，等后续帧或 EOF 收尾
            return;
        }
        rawFinishReason = raw;
        finishReason = mapped;
        // 明确工具调用时立刻补 completed，前端可以马上开始渲染工具卡片
        if (mapped == UnifiedFinishReason.TOOL_CALLS) {
            events.addAll(drainCompleted());
        }
    }

    /**
     * 厂商 finish_reason 文本 → 统一枚举。原生接口用 null / "null" 表示未完，
     * "stop" 表示正常结束，"tool_calls" 表示要调工具，"length" 表示截断。
     * 未知取值按「自然结束」处理并保留原文，避免新增文案把正常回复误判成错误。
     *
     * @param raw 厂商原始结束标识
     * @return 统一结束原因；返回 null 表示「上游明确说还没结束」，调用方应保持原状态
     */
    private UnifiedFinishReason mapFinishReason(String raw) {
        String value = raw.trim().toLowerCase();
        // 原生接口未完帧会给字符串 "null"，与 JSON null 语义相同
        if ("null".equals(value) || value.isEmpty()) {
            return null;
        }
        return switch (value) {
            case "stop", "end_turn", "eos" -> UnifiedFinishReason.STOP;
            case "tool_calls", "function_call", "tool_use" -> UnifiedFinishReason.TOOL_CALLS;
            case "length", "max_tokens" -> UnifiedFinishReason.LENGTH;
            case "content_filter", "safety", "data_inspection_failed" -> UnifiedFinishReason.CONTENT_FILTER;
            case "error", "server_error" -> UnifiedFinishReason.ERROR;
            default -> UnifiedFinishReason.STOP;
        };
    }

    /**
     * 读取 usage 节点。灵积字段名与 OpenAI 一致（input_tokens 出现于部分模型，需一并兼容）。
     * 字段缺失不补 0，保持 null 让计费侧自行判断。
     */
    private void readUsage(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return;
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
