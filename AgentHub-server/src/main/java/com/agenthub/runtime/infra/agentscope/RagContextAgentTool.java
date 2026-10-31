package com.agenthub.runtime.infra.agentscope;

import com.agenthub.rag.application.RagToolContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
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
 */
public final class RagContextAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final Long agentId;

    /**
     * @param delegate 已装配的工具（可再套配额 / 必填闸门）
     * @param agentId  本轮试跑 Agent；空则不绑（调用方应避免）
     */
    public RagContextAgentTool(AgentTool delegate, Long agentId) {
        this.delegate = delegate;
        this.agentId = agentId;
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
        return Mono.defer(() -> {
            RagToolContext.bind(agentId);
            try {
                return delegate.callAsync(param)
                        .doFinally(this::clearOnToolThread);
            } catch (RuntimeException ex) {
                RagToolContext.clear();
                throw ex;
            }
        });
    }

    private void clearOnToolThread(SignalType ignored) {
        RagToolContext.clear();
    }
}
