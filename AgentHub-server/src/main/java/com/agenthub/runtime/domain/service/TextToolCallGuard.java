package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 拦截助手正文里的文本版 tool_call。所属域：runtime。
 * 引擎未真正执行工具时，禁止把 &lt;tool_call&gt; 当最终回答并停在半截；
 * 直接失败收束，写出可读错误，避免 UI 无终态。
 */
public final class TextToolCallGuard {

    /** 常见文本伪调用：&lt;tool_call&gt;...&lt;/tool_call&gt; 或未闭合开标签 */
    private static final Pattern TEXT_TOOL_CALL = Pattern.compile(
            "(?is)<\\s*tool_call\\b|/\\s*tool_call\\s*>");

    private TextToolCallGuard() {
    }

    /**
     * 一轮结束后检查：若正文像文本 tool_call 且没有成功的真工具结果，抛出 TrialFailure。
     *
     * @param turn 已收集的一轮结果，可空
     * @throws TrialFailure 假 tool_call 未执行时
     */
    public static void assertNoOrphanTextToolCall(TrialTurn turn) {
        if (turn == null) {
            return;
        }
        String text = turn.getAssistantText();
        if (!looksLikeTextToolCall(text)) {
            return;
        }
        // 已有真工具成功结果：允许正文残留标签，不二次打断
        if (hasSuccessfulTool(turn.getTraces())) {
            return;
        }
        throw new TrialFailure(
                "模型以文本输出了 tool_call 但工具未真正执行，本轮已中止。请确认工具已完整注入并可调用后重试");
    }

    /**
     * @param text 助手正文，可空
     * @return 含文本版 tool_call 标记时为 true
     */
    public static boolean looksLikeTextToolCall(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return TEXT_TOOL_CALL.matcher(text).find();
    }

    private static boolean hasSuccessfulTool(List<TrialTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return false;
        }
        for (TrialTrace trace : traces) {
            if (trace != null && trace.getType() == EventType.TOOL && trace.isSuccess()) {
                return true;
            }
        }
        return false;
    }
}
