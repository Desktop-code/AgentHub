package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 调用配额与工具结果回灌截断。所属域：runtime。
 * 套在 {@link MeteringModel} 外；obs 仍看计量层收到的完整事件，截断只影响出站 messages。
 */
public final class LimitingModel implements Model {

    private final Model delegate;
    private final RunBudget budget;

    /**
     * @param delegate 计量模型
     * @param budget   本轮配额
     */
    public LimitingModel(Model delegate, RunBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    /**
     * 占 LLM 配额，并截断消息里的工具结果正文后再出站。
     *
     * @param messages 上下文
     * @param tools    工具 schema
     * @param options  生成参数
     * @return 原样转发的响应流
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        try {
            budget.beforeLlm();
        } catch (TrialFailure ex) {
            return Flux.error(ex);
        }
        List<Msg> clipped = ToolResultClipper.clip(messages, budget.limits().toolResultMaxChars());
        return delegate.stream(clipped, tools, options);
    }

    /** {@inheritDoc} */
    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    /** {@inheritDoc} */
    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
