package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按引擎事件顺序收集思考和工具。所属域：runtime。
 * 不依赖 AgentScope 类型，便于单测。空白思考直接丢掉，不造占位推理。
 */
public final class TrialTraceAccumulator {

    private final List<TrialTrace> traces = new ArrayList<>();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<String, ToolBuf> open = new LinkedHashMap<>();

    /**
     * 追加一段思考增量。空增量忽略。
     *
     * @param delta 引擎给出的思考片段，可空
     */
    public void onThinkingDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        thinking.append(delta);
    }

    /**
     * 一个思考块结束。没有正文就不产生 reasoning。
     */
    public void onThinkingEnd() {
        flushThinking();
    }

    /**
     * 工具开始。先落已结束的思考，保证它排在该工具前面。
     *
     * @param toolCallId 调用 id，用来和结果对齐
     * @param name       工具名
     */
    public void onToolStart(String toolCallId, String name) {
        flushThinking();
        open.putIfAbsent(key(toolCallId, name), new ToolBuf(name));
    }

    /**
     * 追加工具入参片段。落库前会脱敏，这里先原样拼起来。
     *
     * @param toolCallId 调用 id
     * @param name       工具名
     * @param delta      参数增量，可空
     */
    public void onToolArgDelta(String toolCallId, String name, String delta) {
        ToolBuf buf = open.computeIfAbsent(key(toolCallId, name), ignored -> new ToolBuf(name));
        if (name != null && !name.isBlank()) {
            buf.name = name;
        }
        if (delta != null && !delta.isEmpty()) {
            buf.arguments.append(delta);
        }
    }

    /**
     * 追加工具结果正文。这里只收结果文本。
     *
     * @param toolCallId 调用 id
     * @param name       工具名
     * @param delta      结果片段
     */
    public void onToolResultDelta(String toolCallId, String name, String delta) {
        ToolBuf buf = open.computeIfAbsent(key(toolCallId, name), ignored -> new ToolBuf(name));
        if (name != null && !name.isBlank()) {
            buf.name = name;
        }
        if (delta != null && !delta.isEmpty()) {
            buf.result.append(delta);
        }
    }

    /**
     * 工具结束。成败由调用方根据引擎状态判断，本类不猜测成功。
     *
     * @param toolCallId 调用 id
     * @param name       工具名
     * @param success    引擎状态为成功时为 true
     * @return 刚落上的工具步骤，参数已脱敏
     */
    public TrialTrace onToolResultEnd(String toolCallId, String name, boolean success) {
        flushThinking();
        ToolBuf buf = open.remove(key(toolCallId, name));
        if (buf == null) {
            buf = new ToolBuf(name);
        } else if (name != null && !name.isBlank()) {
            buf.name = name;
        }
        return addTool(buf, success);
    }

    /**
     * @return 缓冲或已落列表里已经有非空思考
     */
    public boolean hasReasoning() {
        if (!thinking.toString().isBlank()) {
            return true;
        }
        return traces.stream().anyMatch(item -> item.getType() == EventType.REASONING);
    }

    /**
     * 流里没有思考、但最终消息带了 ThinkingBlock 时，把这些正文插到步骤最前。
     * 已经有思考则不再加，避免和增量重复。
     *
     * @param texts 引擎思考正文，可空
     */
    public void prependReasoningIfAbsent(List<String> texts) {
        if (texts == null || texts.isEmpty() || hasReasoning()) {
            return;
        }
        List<TrialTrace> extra = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            extra.add(reasoning(text.trim()));
        }
        if (!extra.isEmpty()) {
            traces.addAll(0, extra);
        }
    }

    /**
     * 收尾。未结束的思考若有正文会补上；没等到结果的工具记为失败。
     *
     * @return 按出现顺序的步骤，可能为空
     */
    public List<TrialTrace> finish() {
        flushThinking();
        for (ToolBuf buf : open.values()) {
            addTool(buf, false);
        }
        open.clear();
        return traces;
    }

    private void flushThinking() {
        String text = thinking.toString().trim();
        thinking.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        traces.add(reasoning(text));
    }

    private TrialTrace reasoning(String text) {
        TrialTrace trace = new TrialTrace();
        trace.setType(EventType.REASONING);
        trace.setContent(text);
        return trace;
    }

    private TrialTrace addTool(ToolBuf buf, boolean success) {
        TrialTrace trace = new TrialTrace();
        trace.setType(EventType.TOOL);
        trace.setName(buf.name);
        trace.setSuccess(success);
        trace.setArguments(TrialTexts.redactArgs(buf.arguments.toString()));
        trace.setContent(buf.result.toString());
        traces.add(trace);
        return trace;
    }

    /**
     * 有调用 id 时按 id 对齐。没有 id 时退回工具名，同名并发调用可能并成一条。
     */
    private String key(String toolCallId, String name) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            return "id:" + toolCallId;
        }
        String safe = name == null ? "" : name.trim();
        return "name:" + safe;
    }

    private static final class ToolBuf {
        private String name;
        private final StringBuilder arguments = new StringBuilder();
        private final StringBuilder result = new StringBuilder();

        private ToolBuf(String name) {
            this.name = name;
        }
    }
}
