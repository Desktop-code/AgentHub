package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.InternalToolNames;
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
 * ThinkingBlock 记 reasoning。工具调用之前的普通正文也记 reasoning：那是为了继续调工具说的中途说明，不是终稿。
 * 只有最后一次工具之后留下的正文才是终答，并且整轮只推一次。
 * 工具入参会收下并脱敏。
 */
public final class TrialEventCollector {

    private final TrialTraceAccumulator traces = new TrialTraceAccumulator();
    private final TrialProgress progress;
    private final RunBudget budget;
    /**
     * true：工具前的正文先记下，遇到工具再归入推理，终答只在收尾推一次。
     * false：无工具的单轮调用，正文仍按增量直接当终答推，避免汇总整段闪现。
     */
    private final boolean narrationBeforeTool;
    /** 构造时取走当前 trace。工具事件若换线程，仍写到同一次调用上 */
    private final TrialSpanSink obsSpans = TrialSpanSink.capture();
    /** 最近一次工具之后的正文。后面若又调工具，整段改记 reasoning，不进终答 */
    private final StringBuilder pendingText = new StringBuilder();
    private final List<String> messageThinking = new ArrayList<>();
    /** 已经归进推理的最后一段中途说明。终态消息若原样再带一次，不能再当成终答 */
    private String lastFlushedNarration = "";
    private String assistantText;
    private Integer promptTokens;
    private Integer completionTokens;
    private boolean streamedAssistant;
    private int emittedTools;

    /**
     * 单测和同步收集用，不推增量。
     */
    public TrialEventCollector() {
        this(null, null, true);
    }

    /**
     * @param progress 边收边推。可空，空则只在结束时返回整轮
     */
    public TrialEventCollector(TrialProgress progress) {
        this(progress, null, true);
    }

    /**
     * 无工具单轮：正文增量直接当终答推。ReAct 多轮要走 {@link #TrialEventCollector(TrialProgress, RunBudget, boolean)}。
     *
     * @param progress 边收边推。可空
     * @param budget   可空；有则把超轮事件记成撞限
     */
    public TrialEventCollector(TrialProgress progress, RunBudget budget) {
        this(progress, budget, false);
    }

    /**
     * @param progress            边收边推。可空
     * @param budget              可空；有则把超轮事件记成撞限
     * @param narrationBeforeTool true 时工具前正文归入推理，终答只在收尾出现一次
     */
    public TrialEventCollector(TrialProgress progress, RunBudget budget, boolean narrationBeforeTool) {
        this.progress = progress;
        this.budget = budget;
        this.narrationBeforeTool = narrationBeforeTool;
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
     * 终答只取最后一次工具之后的正文，中途说明留在推理里。
     *
     * @return 助手正文、token、按顺序的思考和工具
     */
    public TrialTurn finish() {
        settleAnswer();
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

    /**
     * 撞限或中断收尾前调用：还没跟工具的那段正文归入推理，避免被当成终答。
     * 已经归过的段落不会再推一遍。
     */
    public void keepPendingAsReasoning() {
        flushPendingAsReasoning();
    }

    private TrialTurn toTurn() {
        // 流式思考优先。只有流里完全没有思考时，才采用最终消息上的 ThinkingBlock
        if (!traces.hasReasoning()) {
            traces.prependReasoningIfAbsent(messageThinking);
        }
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(assistantText == null ? "" : assistantText);
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
            // 工具前的正文是中途说明：先落进推理，再记这次工具，顺序与页面一致
            flushPendingAsReasoning();
            traces.onToolStart(start.getToolCallId(), start.getToolCallName());
            /*
             * 平台内部工具（选项提交）对用户不可见：连「正在调用」胶囊都不画，
             * 前端只该看到正文与 chips。obs 也不开 span，避免 Trace 里出现一条无名机器调用。
             */
            if (InternalToolNames.isQuiet(start.getToolCallName())) {
                return;
            }
            obsSpans.onStart(start.getToolCallId(), start.getToolCallName());
            if (progress != null) {
                progress.phase("tool");
                /*
                 * 同步推一条「工具开始」：前端据此先画「正在调用 xxx…」胶囊。
                 * 只有这里（引擎真的开始执行工具）才是这条事件的正确时机；
                 * 结果到达时 toolEnd 会用同一个工具名把胶囊补成成功 / 失败。
                 */
                progress.toolStart(start.getToolCallName());
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
        // 静音工具：onToolResultEnd 已返回 null，这里不再落进度 / 不再计数
        if (InternalToolNames.isQuiet(end.getToolCallName())) {
            return;
        }
        obsSpans.onEnd(end.getToolCallId(), end.getToolCallName(), tool);
        emittedTools++;
        if (progress != null && tool != null) {
            progress.phase("tool");
            // 时间线与回灌同截，避免暗示模型看见了未回灌全文；obs span 仍用 traces 原文
            String body = displayToolResult(tool.getContent());
            progress.tool(tool.getName(), tool.getArguments(), withFirstFailure(body, tool.isSuccess()),
                    tool.isSuccess());
        }
    }

    /**
     * 失败结果后面附上本轮首错，让「胶囊详情」与「完整链路」都能看到真正的第一次异常。
     *
     * <p>为什么必须附：熔断文案（{@code consecutive_fail_abort}）只说明次数，
     * 后续每一路失败都只写这一句，首错就被盖掉了。这里把 {@link RunBudget#firstFailure()}
     * 拼在失败结果末尾；成功结果不附，避免噪音。
     *
     * @param body    工具结果正文（已脱敏）
     * @param success 是否成功
     * @return 需要展示的结果正文
     */
    private String withFirstFailure(String body, boolean success) {
        if (success || budget == null) {
            return body;
        }
        RunBudget.FirstFailure first = budget.firstFailure();
        if (first == null) {
            return body;
        }
        String raw = body == null ? "" : body.trim();
        // 首错本身已在正文里（第一次失败那条）就不重复拼
        if (!first.detail().isBlank() && raw.contains(first.detail())) {
            return raw;
        }
        String note = "首次失败原因（" + first.kind() + "）：" + first.detail();
        return raw.isEmpty() ? note : raw + "\n\n" + note;
    }

    private void textDelta(AgentEvent event) {
        if (!(event instanceof TextBlockDeltaEvent delta) || delta.getDelta() == null || delta.getDelta().isEmpty()) {
            return;
        }
        // 无工具单轮：正文就是终答，按增量推，避免整段到结束才闪出来
        if (!narrationBeforeTool) {
            pendingText.append(delta.getDelta());
            streamedAssistant = true;
            if (progress != null) {
                progress.phase("answering");
                progress.assistant(delta.getDelta());
            }
            return;
        }
        // 先别当终答推。这段后面若接着调工具，会整段改记推理；只有跑完仍留着的才是终稿
        if (progress != null && pendingText.isEmpty()) {
            progress.phase("reasoning");
        }
        pendingText.append(delta.getDelta());
    }

    /**
     * 终答只保留「最后一次工具之后」的正文。
     * 引擎在结束消息里若把已经归进推理的中途说明再带一遍，这里丢掉，避免同一段出现两次。
     */
    private void settleAnswer() {
        String pending = pendingText.toString();
        pendingText.setLength(0);
        if (!pending.isBlank()) {
            assistantText = pending;
            return;
        }
        if (assistantText != null && !assistantText.isBlank() && assistantText.equals(lastFlushedNarration)) {
            assistantText = "";
        }
    }

    /**
     * 把尚未定性的正文整段记成推理，并清空缓冲。空白不产生步骤。
     */
    private void flushPendingAsReasoning() {
        String text = pendingText.toString();
        pendingText.setLength(0);
        if (text.isBlank()) {
            return;
        }
        lastFlushedNarration = text;
        traces.onThinkingDelta(text);
        traces.onThinkingEnd();
        if (progress != null) {
            progress.phase("reasoning");
            progress.reasoning(text);
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
            // 补推的这条也要带首错：熔断短路常从这条路进来，不带就只剩一句「撞限」
            progress.tool(trace.getName(), trace.getArguments(),
                    withFirstFailure(displayToolResult(trace.getContent()), trace.isSuccess()),
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
        // 仅在没有流式正文时兜底。有增量时以最后一次工具之后的缓冲为准，避免整段历史被当成终答
        if (pendingText.isEmpty() && (assistantText == null || assistantText.isBlank())) {
            assistantText = msg.getTextContent();
        }
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
