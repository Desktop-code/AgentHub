package com.agenthub.runtime.infra.agentscope;

import com.agenthub.obs.domain.ObsToolSpanContext;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.rag.application.RagToolContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Map;

/**
 * 把本轮 Agent id 绑到工具执行线程。所属域：runtime 的 infra。
 * <p>
 * AgentScope {@code ToolExecutor} 默认 {@code subscribeOn(boundedElastic)}，
 * 试跑主线程写入的 {@link RagToolContext} ThreadLocal 到工具回调时已不可见，
 * 会导致 {@code searchKnowledgeBase} 误报「没有绑定任何可用知识库」。
 * 本包装在 {@link #callAsync} 入口重绑、结束时清理执行线程上下文（与主线程 finally 互补）。
 * <p>同时把本次工具调用对应的 TOOL span 锚点带入工具线程，
 * 供知识库检索等工具在父 TOOL span 下挂子步骤；非检索工具不读该锚点，行为不变。
 */
public final class RagContextAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(RagContextAgentTool.class);

    private final AgentTool delegate;
    private final Long agentId;
    /**
     * 组装工具时（ReAct 主线程，trace 已 bind）捕获的 trace。
     * <p>为什么不在 {@link #callAsync} 里读 {@link ObsTraceHolder}：AgentScope 的工具调用
     * 组装/下标都在 boundedElastic 上，那里没有 bind 过 trace，读出来恒为 null，
     * 会导致检索子步静默不落库。这里在构造时取一次，随包装对象带到工具线程。
     */
    private final ObsTraceScope trace;

    /**
     * @param delegate 已装配的工具（可再套配额 / 必填闸门）
     * @param agentId  本轮试跑 Agent；空则不绑（调用方应避免）
     */
    public RagContextAgentTool(AgentTool delegate, Long agentId) {
        this(delegate, agentId, ObsTraceHolder.current());
    }

    /**
     * 显式传入 trace：调用方（{@link ToolkitRagContext}）在 ReAct 主线程取好，避免再依赖 ThreadLocal。
     *
     * @param delegate 已装配的工具
     * @param agentId  本轮试跑 Agent；可空
     * @param trace    本轮 trace；可空（评测 / 接口直调无 trace）
     */
    public RagContextAgentTool(AgentTool delegate, Long agentId, ObsTraceScope trace) {
        this.delegate = delegate;
        this.agentId = agentId;
        this.trace = trace;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return delegate.getName();
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    /** {@inheritDoc} */
    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReturnDirect() {
        return delegate.isReturnDirect();
    }

    /**
     * 用 {@link Mono#defer} 把 bind 推迟到订阅时：ToolExecutor 先在主线程调 callAsync 建链，
     * 再 {@code subscribeOn(boundedElastic)}，只有 defer 内才会在工具线程写入 ThreadLocal。
     *
     * @param param 引擎入参
     * @return 原工具结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // callId 在组装时取；trace 用构造时的引用（工具线程上 ObsTraceHolder 不可见）
        String callId = param == null || param.getToolUseBlock() == null
                ? null
                : param.getToolUseBlock().getId();
        String toolName = param == null || param.getToolUseBlock() == null
                ? null
                : param.getToolUseBlock().getName();
        return Mono.defer(() -> {
            RagToolContext.bind(agentId);
            // 工具线程重新绑定本次 TOOL 行：知识库检索子步据此挂到父 span
            ObsTraceScope toolScope = trace != null ? trace : ObsTraceHolder.current();
            Long toolSpanId = toolScope == null ? null : toolScope.toolSpanOf(callId);
            if (toolScope != null && toolSpanId == null) {
                // 有 trace 却没登记到本次 TOOL 行（callId 不一致 / 事件尚未到）：
                // 不做「挂最近一条 TOOL」的兜底（并行调用会挂错），只留一行可排查线索
                log.warn("[obs] 未匹配到 TOOL span，工具内子步将跳过 tool={} callId={}",
                        toolName, callId == null ? "-" : callId);
            }
            ObsToolSpanContext.bind(toolScope, toolSpanId, agentId);
            try {
                return delegate.callAsync(param)
                        .doFinally(this::clearOnToolThread);
            } catch (RuntimeException ex) {
                RagToolContext.clear();
                ObsToolSpanContext.clear();
                throw ex;
            }
        });
    }

    private void clearOnToolThread(SignalType ignored) {
        RagToolContext.clear();
        ObsToolSpanContext.clear();
    }
}
