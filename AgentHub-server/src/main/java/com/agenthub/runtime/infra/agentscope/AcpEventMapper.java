package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一流式事件 → AgentScope 响应帧的映射器。所属域：runtime 的 infra。
 * 为什么单独成类：引擎要的是「逐帧 chunk」，而统一事件是「语义事件」，两者的对应规则有几处非直觉约定，
 * 集中在这里才能一眼看清并单测：
 * <ol>
 *   <li>正文/思考各自翻一帧，分别产出 TextBlock / ThinkingBlock；</li>
 *   <li><b>工具调用只在 {@code tool_call.completed} 上产出 ToolUseBlock</b>：引擎的累积器会把同名或同 id 的
 *       碎片拼起来，逐字投递半截 JSON 会让引擎先看到非法参数；completed 携带的才是可执行参数；</li>
 *   <li>{@code tool_call.delta} 刻意不投递，避免引擎重复计数同一次调用；</li>
 *   <li>{@code error} 直接抛异常，让引擎走失败分支；{@code done} 用一帧空内容承载 finish_reason。</li>
 * </ol>
 * 边界：只出现 Unified* 与 AgentScope 类型，厂商字段名一概不出现。
 * 线程：无状态可复用。
 */
final class AcpEventMapper {

    /** 引擎侧表示「本轮要调工具」的 finish_reason 文本，与 OpenAI 语义一致 */
    private static final String FINISH_TOOL_CALLS = "tool_calls";

    private final ObjectMapper json;

    /**
     * @param json 序列化器，用于把工具参数 JSON 文本还原成引擎要的 Map
     */
    AcpEventMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 统一事件 → 0..N 个引擎响应帧。
     *
     * @param event 统一事件，可空
     * @param start 本轮起始时刻，用于记录引擎侧耗时
     * @return 引擎响应帧；空列表表示该事件不产生帧
     * @throws IllegalStateException 事件为 ERROR（携带脱敏摘要），由调用方转成引擎异常
     */
    List<ChatResponse> toFrames(UnifiedStreamEvent event, Instant start) {
        List<ChatResponse> frames = new ArrayList<>();
        if (event == null || event.getType() == null) {
            return frames;
        }
        ChatUsage usage = toUsage(event.getUsage(), start);
        switch (event.getType()) {
            case MESSAGE_DELTA -> {
                if (hasText(event.getContent())) {
                    frames.add(frame(List.of(TextBlock.builder().text(event.getContent()).build()), usage, null));
                }
            }
            case THINKING_DELTA -> {
                if (hasText(event.getContent())) {
                    frames.add(frame(List.of(ThinkingBlock.builder().thinking(event.getContent()).build()),
                            usage, null));
                }
            }
            case TOOL_CALL_COMPLETED -> {
                ToolUseBlock block = toToolUse(event.getToolCallDelta());
                if (block != null) {
                    frames.add(frame(List.of(block), usage, FINISH_TOOL_CALLS));
                }
            }
            case USAGE -> frames.add(frame(List.of(), usage, null));
            case ERROR -> throw new IllegalStateException(
                    hasText(event.getContent()) ? event.getContent() : "模型调用失败");
            case DONE -> {
                // done 决定本轮结束原因；即便没有内容块也要发一帧，让引擎知道该轮已收尾
                frames.add(frame(List.of(), usage, finishReasonOf(event.getFinishReason())));
            }
            default -> {
                // TOOL_CALL_DELTA 与未来扩展类型：不投递。碎片语义已由 completed 承担
            }
        }
        return frames;
    }

    /**
     * 统一工具调用 → 引擎工具调用块。
     *
     * @param call 统一调用，可空
     * @return 引擎块；名称缺失时返回 null（不可执行）
     */
    private ToolUseBlock toToolUse(UnifiedToolCall call) {
        if (call == null || !hasText(call.getName())) {
            return null;
        }
        // id 缺失时给一个稳定占位，保证回灌 tool_result 时有可用关联键
        String id = hasText(call.getId()) ? call.getId() : "call_" + UUID.randomUUID().toString().replace("-", "");
        return ToolUseBlock.builder()
                .id(id)
                .name(call.getName())
                .input(readArguments(call.getArguments()))
                // content 保留参数原文：引擎的累积器与观测都依赖它
                .content(call.getArguments())
                .build();
    }

    /**
     * 工具参数 JSON 文本 → Map。解析失败给空 Map，参数原文仍随 content 保留。
     *
     * @param argumentsJson 参数字符串，可空
     * @return 参数 Map，不会为 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readArguments(String argumentsJson) {
        if (!hasText(argumentsJson)) {
            return AcpRequestMapper.emptyArguments();
        }
        try {
            Map<String, Object> parsed = json.readValue(argumentsJson, Map.class);
            return parsed == null ? AcpRequestMapper.emptyArguments() : parsed;
        } catch (Exception ex) {
            return AcpRequestMapper.emptyArguments();
        }
    }

    /**
     * 统一用量 → 引擎用量。引擎该对象是基本类型，缺字段只能补 0。
     *
     * @param usage 统一用量，可空
     * @param start 本轮起始时刻
     * @return 引擎用量；统一用量缺失时返回 null，让引擎沿用上一帧
     */
    private ChatUsage toUsage(UnifiedUsage usage, Instant start) {
        if (usage == null) {
            return null;
        }
        return ChatUsage.builder()
                .inputTokens(usage.getPrompt() == null ? 0 : usage.getPrompt())
                .outputTokens(usage.getCompletion() == null ? 0 : usage.getCompletion())
                .time(Duration.between(start, Instant.now()).toMillis() / 1000.0)
                .build();
    }

    /**
     * 组装一帧引擎响应。
     *
     * @param blocks       内容块，允许空列表
     * @param usage        用量，可空
     * @param finishReason 结束原因，可空（中间帧）
     * @return 引擎响应
     */
    private ChatResponse frame(List<ContentBlock> blocks, ChatUsage usage, String finishReason) {
        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .content(blocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }

    /**
     * 统一结束原因 → 引擎 finish_reason 文本。引擎只做粗粒度判断，保持与 OpenAI 语义一致最省事。
     *
     * @param reason 统一原因，可空
     * @return 引擎可读的结束原因，不会为 null
     */
    private String finishReasonOf(UnifiedFinishReason reason) {
        if (reason == null) {
            return "error";
        }
        return switch (reason) {
            case STOP -> "stop";
            case TOOL_CALLS -> FINISH_TOOL_CALLS;
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case ERROR, TIMEOUT, CANCELLED -> "error";
        };
    }

    /**
     * @return 是否是可投递的非空文本
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
