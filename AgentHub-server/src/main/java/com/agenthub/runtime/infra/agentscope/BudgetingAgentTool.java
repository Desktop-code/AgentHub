package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 套在已注册工具外的配额包装。所属域：runtime。
 * 计次、连续失败中止；结果正文不截断（截断只发生在进模型消息路径）。
 */
public final class BudgetingAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final RunBudget budget;

    /**
     * @param delegate 原工具
     * @param budget   本轮配额
     */
    public BudgetingAgentTool(AgentTool delegate, RunBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
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
     * 先占配额再调原工具；失败计入连续失败。
     *
     * @param param 引擎入参
     * @return 原结果；撞限时以错误 Mono 结束
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            budget.beforeTool();
        } catch (TrialFailure ex) {
            return Mono.error(ex);
        }
        return delegate.callAsync(param)
                .doOnNext(result -> budget.afterTool(isSuccess(result)))
                .doOnError(error -> {
                    if (!(error instanceof TrialFailure)) {
                        budget.afterTool(false);
                    }
                });
    }

    private static boolean isSuccess(ToolResultBlock result) {
        if (result == null) {
            return false;
        }
        ToolResultState state = result.getState();
        // null 按成功计（部分工具未设 state）；RUNNING 不应在 callAsync 完成时出现
        return state == null || state == ToolResultState.SUCCESS;
    }
}
