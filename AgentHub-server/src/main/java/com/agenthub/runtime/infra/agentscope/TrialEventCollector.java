package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTraceAccumulator;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 AgentScope 事件收成可落库的一轮结果。所属域：runtime 的 infra。
 * 只有 ThinkingBlock 才记 reasoning。普通正文即使像思考，也只进助手回复。
 * 工具入参会收下并脱敏。模型没给思考块时，结果里不会有 reasoning。
 */
public final class TrialEventCollector {

    private final TrialTraceAccumulator traces = new TrialTraceAccumulator();
    private final TrialProgress progress;
    private final RunBudget budget;
    /** 构造时取走当前 trace。工具事件若换线程，仍写到同一次调用上 */
    private final TrialSpanSink obsSpans = TrialSpanSink.capture();
    private final StringBuilder textDeltas = new StringBuilder();
    private final List<String> messageThinking = new ArrayList<>();
    private String assistantText;
    private Integer promptTokens;
    private Integer completionTokens;
    private boolean streamedAssistant;
    private int emittedTools;

    /**
     * 单测和同步收集用，不推增量。
     */
    public TrialEventCollector() {
        this(null, null);
    }

    /**
     * @param progress 边收边推。可空，空则只在结束时返回整轮
     */
    public TrialEventCollector(TrialProgress progress) {
        this(progress, null);
    }

    /**
     * @param progress 边收边推。可空
     * @param budget   可空；有则把超轮事件记成撞限
     */
    public TrialEventCollector(TrialProgress progress, RunBudget budget) {
        this.progress = progress;
        this.budget = budget;
    }

    /**
     * @param events 一轮 streamEvents，可空
     * @return 助手正文、token、按顺序的思考和工具
     */
    public static TrialTurn collect(List<AgentEvent> events) {
        TrialEventCollector collector = new TrialEventCollector();
        if (events != null) {
            for (AgentEvent event : events) {
                collector.accept(event);
            }
        }
        return collector.finish();
    }

    /**
     * 收一条引擎事件。思考和正文增量会立刻交给 progress。
     *
     * @param event AgentScope 事件，可空
     */
    public void accept(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }
        switch (event.getType()) {
            case THINKING_BLOCK_DELTA -> thinkingDelta(event);
            case THINKING_BLOCK_END -> traces.onThinkingEnd();
            case TOOL_CALL_START -> toolStart(event);
            case TOOL_CALL_DELTA -> toolArg(event);
            case TOOL_RESULT_TEXT_DELTA -> toolDelta(event);
            case TOOL_RESULT_END -> toolEnd(event);
            case TEXT_BLOCK_DELTA -> textDelta(event);
            case AGENT_RESULT -> agentResult(event);
            case EXCEED_MAX_ITERS -> markMaxRounds(event);
            default -> {
                // 生命周期事件不入库
            }
        }
    }

    private void markMaxRounds(AgentEvent event) {
        if (budget != null && event instanceof ExceedMaxItersEvent) {
            budget.markMaxRounds();
        }
    }

    /**
     * 收尾。流里没推过的最终思考和正文在这里补一次，避免只有整包结果时前端空白。
     *
     * @return 助手正文、token、按顺序的思考和工具
     */
    public TrialTurn finish() {
        boolean streamedReasoning = traces.hasReasoning();
        TrialTurn turn = toTurn();
        if (progress == null) {
            return turn;
        }
        if (!streamedReasoning && turn.getTraces() != null) {
            for (TrialTrace trace : turn.getTraces()) {
                if (trace.getType() == EventType.REASONING && trace.getContent() != null && !trace.getContent().isBlank()) {
                    progress.phase("reasoning");
                    progress.reasoning(trace.getContent());
                }
            }
        }
        emitNewTools(turn.getTraces());
        if (!streamedAssistant && turn.getAssistantText() != null && !turn.getAssistantText().isBlank()) {
            progress.phase("answering");
            progress.assistant(turn.getAssistantText());
        }
        return turn;
    }

    private TrialTurn toTurn() {
        // 流式思考优先。只有流里完全没有思考时，才采用最终消息上的 ThinkingBlock
        if (!traces.hasReasoning()) {
            traces.prependReasoningIfAbsent(messageThinking);
        }
        TrialTurn turn = new TrialTurn();
        String text = assistantText;
        if (text == null || text.isBlank()) {
            text = textDeltas.toString();
        }
        turn.setAssistantText(text == null ? "" : text);
        turn.setPromptTokens(promptTokens);
        turn.setCompletionTokens(completionTokens);
        // 落库时间线与 SSE 同截时用；obs 读 traces 原文
        if (budget != null) {
            turn.setToolResultMaxChars(budget.limits().toolResultMaxChars());
        }
        turn.setTraces(traces.finish());
        return turn;
    }

    private void thinkingDelta(AgentEvent event) {
        if (event instanceof ThinkingBlockDeltaEvent delta && delta.getDelta() != null) {
            traces.onThinkingDelta(delta.getDelta());
            if (progress != null && !delta.getDelta().isEmpty()) {
                progress.phase("reasoning");
                progress.reasoning(delta.getDelta());
            }
        }
    }

    private void toolStart(AgentEvent event) {
        if (event instanceof ToolCallStartEvent start) {
            traces.onToolStart(start.getToolCallId(), start.getToolCallName());
            obsSpans.onStart(start.getToolCallId(), start.getToolCallName());
            if (progress != null) {
                progress.phase("tool");
            }
        }
    }

    private void toolArg(AgentEvent event) {
        if (event instanceof ToolCallDeltaEvent delta) {
            traces.onToolArgDelta(delta.getToolCallId(), delta.getToolCallName(), delta.getDelta());
        }
    }

    private void toolDelta(AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent delta) {
            traces.onToolResultDelta(delta.getToolCallId(), delta.getToolCallName(), delta.getDelta());
        }
    }

    private void toolEnd(AgentEvent event) {
        if (!(event instanceof ToolResultEndEvent end)) {
            return;
        }
        boolean success = end.getState() == ToolResultState.SUCCESS;
        TrialTrace tool = traces.onToolResultEnd(end.getToolCallId(), end.getToolCallName(), success);
        obsSpans.onEnd(end.getToolCallId(), end.getToolCallName(), tool);
        emittedTools++;
        if (progress != null && tool != null) {
            progress.phase("tool");
            // 时间线与回灌同截，避免暗示模型看见了未回灌全文；obs span 仍用 traces 原文
            progress.tool(tool.getName(), tool.getArguments(), displayToolResult(tool.getContent()), tool.isSuccess());
        }
    }

    private void textDelta(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null && !delta.getDelta().isEmpty()) {
            textDeltas.append(delta.getDelta());
            streamedAssistant = true;
            if (progress != null) {
                progress.phase("answering");
                progress.assistant(delta.getDelta());
            }
        }
    }

    /**
     * 工具结束时把新增的那条推出去。参数已在累积器里脱敏。
     */
    private void emitNewTools(List<TrialTrace> all) {
        if (progress == null || all == null) {
            return;
        }
        int seen = 0;
        for (TrialTrace trace : all) {
            if (trace.getType() != EventType.TOOL) {
                continue;
            }
            seen++;
            if (seen <= emittedTools) {
                continue;
            }
            progress.phase("tool");
            progress.tool(trace.getName(), trace.getArguments(), displayToolResult(trace.getContent()),
                    trace.isSuccess());
        }
        emittedTools = seen;
    }

    /**
     * 试跑时间线展示用：与 {@link ToolResultClipper} 回灌规则一致。
     * traces / obs 仍保留未按 tool_result_max_chars 截断的正文。
     */
    private String displayToolResult(String raw) {
        if (budget == null) {
            return raw;
        }
        return ToolResultClipper.clipText(raw, budget.limits().toolResultMaxChars());
    }

    private void agentResult(AgentEvent event) {
        if (!(event instanceof AgentResultEvent result) || result.getResult() == null) {
            return;
        }
        Msg msg = result.getResult();
        assistantText = msg.getTextContent();
        applyUsage(msg.getChatUsage() != null ? msg.getChatUsage() : msg.getUsage());
        messageThinking.clear();
        messageThinking.addAll(thinkingBlocks(msg));
    }

    /**
     * 只认 ThinkingBlock。文本块不算思考。
     */
    private List<String> thinkingBlocks(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof ThinkingBlock thinking
                    && thinking.getThinking() != null
                    && !thinking.getThinking().isBlank()) {
                found.add(thinking.getThinking());
            }
        }
        return found;
    }

    private void applyUsage(ChatUsage usage) {
        if (usage == null) {
            return;
        }
        if (usage.getInputTokens() >= 0) {
            turnTokens(usage.getInputTokens(), true);
        }
        if (usage.getOutputTokens() >= 0) {
            turnTokens(usage.getOutputTokens(), false);
        }
    }

    private void turnTokens(int value, boolean prompt) {
        if (prompt) {
            promptTokens = value;
        } else {
            completionTokens = value;
        }
    }

    /**
     * 给单测看最终步骤里有没有 reasoning。生产路径用 {@link #collect(List)}。
     *
     * @param traces 已收集步骤
     * @return 存在 reasoning 时为 true
     */
    public static boolean hasReasoning(List<TrialTrace> traces) {
        if (traces == null) {
            return false;
        }
        return traces.stream().anyMatch(item -> item.getType() == EventType.REASONING);
    }
}
