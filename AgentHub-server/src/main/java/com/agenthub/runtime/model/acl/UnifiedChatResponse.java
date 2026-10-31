package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一非流式对话结果。所属域：runtime（模型出站 ACL）。
 * 由 Adapter 从厂商响应映射得到；ReAct 循环据此判断是否继续执行工具，
 * finishReason=TOOL_CALLS 时 toolCalls 必须非空，否则视为 Adapter 映射错误。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedChatResponse {

    /**
     * 助手消息（role=assistant）。正文为空但带 toolCalls 是合法情况，
     * 表示模型直接发起工具调用而没有文本。
     */
    private UnifiedMessage message;

    /** 本次响应中的工具调用；与 message.toolCalls 语义相同，这里单独暴露便于执行侧直接取用 */
    @Builder.Default
    private List<UnifiedToolCall> toolCalls = new ArrayList<>();

    /** 结束原因；上游缺失结束标识时由 Gateway 兜底为 ERROR / TIMEOUT，不允许为 null */
    private UnifiedFinishReason finishReason;

    /** 用量；上游未返回时为 null，不伪造 0 */
    private UnifiedUsage usage;

    /** 厂商专有字段（如缓存命中 token、stop_sequence），业务默认忽略未知键 */
    private Map<String, Object> extensions;
}
