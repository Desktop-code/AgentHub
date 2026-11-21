package com.agenthub.runtime.infra.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 用装配期生成的说明替换工具自带的 {@code @Tool(description)}。所属域：runtime 的 infra。
 * <p>
 * 只改「模型看到的工具说明」这一个字段，执行、参数 schema、只读与直返标记全部透传原工具。
 * 用在需要随本轮状态变化的工具上（例如内置检索工具要在说明里列出当前绑定的知识库），
 * 注解写不出这类动态清单，又不宜把知识库信息固化成编译期常量。
 * <p>
 * 线程安全：不可变包装，可在多轮之间共享同一实例。
 */
public final class DescriptionOverrideAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final String description;

    /**
     * @param delegate    已注册的原工具，负责真正调用
     * @param description 本轮给模型的说明；空则回落到原工具描述
     */
    public DescriptionOverrideAgentTool(AgentTool delegate, String description) {
        this.delegate = delegate;
        this.description = description == null || description.isBlank() ? null : description;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return delegate.getName();
    }

    /**
     * @return 装配期生成的说明；未提供时用原工具描述，避免把工具说明变成空串
     */
    @Override
    public String getDescription() {
        return description != null ? description : delegate.getDescription();
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
     * @param param 引擎入参
     * @return 原工具结果，不做任何加工
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }
}
