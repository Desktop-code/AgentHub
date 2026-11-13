package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 套在已注册工具外的配额包装。所属域：runtime。
 * 计次、连续失败中止；结果正文不截断（截断只发生在进模型消息路径）。
 * <p>
 * 熔断作用域：连败计数按 {@code owner}（本轮专家）归属，专家切换即清零。
 * 一把 budget 只服务一个专家，这里显式传 owner 是为了把「不跨专家连坐」写成契约。
 */
public final class BudgetingAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BudgetingAgentTool.class);

    private final AgentTool delegate;
    private final RunBudget budget;
    /** 本轮专家归属（id / 展示名），用于把连续失败计数限定在本专家内 */
    private final String owner;

    /**
     * @param delegate 原工具
     * @param budget   本轮配额
     */
    public BudgetingAgentTool(AgentTool delegate, RunBudget budget) {
        this(delegate, budget, "");
    }

    /**
     * @param delegate 原工具
     * @param budget   本轮配额
     * @param owner    本轮专家归属；空串表示不区分（老调用点）
     */
    public BudgetingAgentTool(AgentTool delegate, RunBudget budget, String owner) {
        this.delegate = delegate;
        this.budget = budget;
        this.owner = owner == null ? "" : owner.trim();
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
                .doOnNext(result -> budget.afterTool(owner, isSuccess(result)))
                .doOnError(error -> {
                    if (!(error instanceof TrialFailure)) {
                        budget.afterTool(owner, false);
                    }
                    // 工具链里的异常常被引擎转成错误块丢掉栈：这里带 Throwable 打一次，
                    // 才能看出是参数、网络还是工具内部实现炸了
                    log.warn("工具执行异常 tool={} type={} cause={}",
                            safeName(), error.getClass().getSimpleName(), CauseDigest.of(error), error);
                });
    }

    /**
     * @return 工具名；委托对象抛异常时退回占位，日志本身不允许再失败
     */
    private String safeName() {
        try {
            return delegate.getName();
        } catch (RuntimeException ex) {
            return "未知工具";
        }
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
