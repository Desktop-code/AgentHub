package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一流式事件。所属域：runtime（模型出站 ACL）。
 * 一条上游 chunk 可能映射成 1..N 个本对象（例如同时含思考与正文，或同时含正文和工具调用分片），
 * 因此 Adapter 的流式契约返回 List 而非单值。
 * 结束约定：一次流必须且只能以一个 type=DONE 的事件收尾（正常或降级），由 Gateway 强制补齐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedStreamEvent {

    /** 事件类型；为 null 视为映射错误，下游按未知类型忽略 */
    private UnifiedStreamEventType type;

    /**
     * 文本增量或错误说明。
     * message.delta / thinking.delta 时是增量片段；error 时是脱敏后的错误摘要；
     * 其它类型一般为 null。
     */
    private String content;

    /** 工具调用分片；tool_call.delta 可只有 id/name/arguments 之一，completed 时参数完整 */
    private UnifiedToolCall toolCallDelta;

    /** 结束原因。仅 done 事件必带；error 事件可带以便上游已给明确原因时透传 */
    private UnifiedFinishReason finishReason;

    /** 用量。仅 usage / done 事件可能出现，其它类型为 null */
    private UnifiedUsage usage;

    /** 扩展载荷（厂商专有字段、traceId 等）；业务默认忽略未知键，禁止依赖其存在 */
    @Builder.Default
    private Map<String, Object> extensions = new LinkedHashMap<>();

    /**
     * 构造正文增量事件。
     *
     * @param delta 本次新增的正文片段
     * @return message.delta 事件
     */
    public static UnifiedStreamEvent messageDelta(String delta) {
        return UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.MESSAGE_DELTA)
                .content(delta)
                .build();
    }

    /**
     * 构造思考增量事件。
     *
     * @param delta 本次新增的思考片段
     * @return thinking.delta 事件
     */
    public static UnifiedStreamEvent thinkingDelta(String delta) {
        return UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.THINKING_DELTA)
                .content(delta)
                .build();
    }

    /**
     * 构造结束事件。正常结束与各类兜底结束都走这里，保证前端只有一个终点。
     *
     * @param reason 结束原因，禁止传 null；上游无原因时由调用方给 TIMEOUT / ERROR
     * @param usage  可为 null，结束前已发过 usage 事件时不必重复塞
     * @return done 事件
     */
    public static UnifiedStreamEvent done(UnifiedFinishReason reason, UnifiedUsage usage) {
        return UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.DONE)
                .finishReason(reason)
                .usage(usage)
                .build();
    }

    /**
     * 构造错误事件。此处只承载摘要，禁止放堆栈或密钥；发送后仍需补 done。
     *
     * @param summary  脱敏后的错误摘要
     * @param reason   可空；上游已给明确原因时透传，否则由 done 兜底
     * @return error 事件
     */
    public static UnifiedStreamEvent error(String summary, UnifiedFinishReason reason) {
        return UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.ERROR)
                .content(summary)
                .finishReason(reason)
                .build();
    }
}
