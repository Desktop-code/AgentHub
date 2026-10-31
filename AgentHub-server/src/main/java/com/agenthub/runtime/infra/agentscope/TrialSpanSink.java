package com.agenthub.runtime.infra.agentscope;

import com.agenthub.obs.domain.ObsSpanStack;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.service.TrialTexts;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 把一次工具执行记成 TOOL 步骤。所属域：runtime 的 infra。
 * 创建时捕获当前子链 {@link ObsSpanStack}：工具回调换线程后仍用同一 parent，不读 ThreadLocal。
 * 没有打开 trace 时直接返回。入参/出参分列写入，只打码，不走事件摘要的长度截断。
 */
public final class TrialSpanSink {

    private final ObsTraceScope scope;
    /** 创建收集器线程上的子链 stack；并行时每子各一份 */
    private final ObsSpanStack stack;
    private final ConcurrentHashMap<String, Deque<Long>> open = new ConcurrentHashMap<>();

    private TrialSpanSink(ObsTraceScope scope, ObsSpanStack stack) {
        this.scope = scope;
        this.stack = stack;
    }

    /**
     * 在创建收集器的线程上取当前 trace 与 stack。之后工具回调可能换线程，只使用这些引用。
     *
     * @return 可能没有 trace 的接收器
     */
    public static TrialSpanSink capture() {
        ObsTraceScope scope = ObsTraceHolder.current();
        return new TrialSpanSink(scope, scope == null ? null : scope.stack());
    }

    /**
     * 工具开始时先落一条 RUNNING，结束再改终态。
     *
     * @param toolCallId 引擎给出的调用 id，可空
     * @param name       工具名，可空
     */
    public void onStart(String toolCallId, String name) {
        if (scope == null) {
            return;
        }
        Long parentId = stack == null ? scope.implicitParent() : stack.parentId();
        Long spanId = scope.open(SpanType.TOOL, toolName(name), agentOf(), parentId, null);
        if (spanId != null) {
            open.computeIfAbsent(key(toolCallId, name), ignored -> new ConcurrentLinkedDeque<>()).addLast(spanId);
        }
    }

    /**
     * @param toolCallId 与开始时相同的 id，可空
     * @param name       工具名
     * @param tool       累积器里的结果，可空
     */
    public void onEnd(String toolCallId, String name, TrialTrace tool) {
        if (scope == null) {
            return;
        }
        Deque<Long> ids = open.get(key(toolCallId, name));
        Long spanId = ids == null ? null : ids.pollFirst();
        boolean success = tool != null && tool.isSuccess();
        if (spanId == null) {
            Long parentId = stack == null ? scope.implicitParent() : stack.parentId();
            spanId = scope.open(SpanType.TOOL, toolName(name), agentOf(), parentId, null);
        }
        SpanClose close = new SpanClose();
        close.setStatus(success ? ObsStatus.OK : ObsStatus.ERROR);
        String arguments = tool == null ? null : tool.getArguments();
        String result = tool == null ? null : tool.getContent();
        close.setInputText(TrialTexts.redactArgs(arguments));
        close.setOutputText(result == null || result.isBlank() ? null : TrialTexts.redact(result));
        if (!success) {
            close.setErrorSummary(close.getOutputText() != null ? close.getOutputText() : "工具调用失败");
        }
        close.setToolName(toolName(name));
        close.setToolCallId(blankToNull(toolCallId));
        close.setRetryCount(0);
        close.setToolSuccess(success ? 1 : 0);
        close.setAttrsJson(ObsAttrs.withBrief(null,
                success ? "执行工具 " + toolName(name) : "工具失败 " + toolName(name)));
        scope.close(spanId, close);
    }

    private Long agentOf() {
        return stack == null ? null : stack.stepAgentId();
    }

    private String key(String toolCallId, String name) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            return toolCallId;
        }
        return "name:" + toolName(name);
    }

    private String toolName(String name) {
        return name == null || name.isBlank() ? "未命名工具" : name.trim();
    }

    private String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
