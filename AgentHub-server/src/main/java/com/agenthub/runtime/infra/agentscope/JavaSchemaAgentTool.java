package com.agenthub.runtime.infra.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

/**
 * 用 Java 元数据生成的 parameters 替换引擎扫描结果。所属域：runtime 的 infra。
 * 执行仍走原工具；只改模型看到的 JSON Schema，补上 {@code @ToolParam} 没有的数值约束。
 */
public final class JavaSchemaAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final Map<String, Object> parameters;

    /**
     * @param delegate   已注册的原工具，负责真正调用
     * @param parameters {@link BuiltinToolSchemas} 生成的 schema，调用期间不可改
     */
    public JavaSchemaAgentTool(AgentTool delegate, Map<String, Object> parameters) {
        this.delegate = delegate;
        this.parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(parameters);
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

    /**
     * @return Java 注解生成的参数 schema，不读 tool_item
     */
    @Override
    public Map<String, Object> getParameters() {
        return parameters;
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
     * @return 原工具结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }
}
