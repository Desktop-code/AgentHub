package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 从已索引的 BUILTIN Bean 取 schema。所属域：runtime 的 infra。
 * 只服务试跑注入和启动回填，不改 MCP 同步进库的 schema。
 */
@Component
public class BuiltinSchemaSourceImpl implements BuiltinSchemaSource {

    private final BuiltinToolBeans beans;

    /**
     * @param beans 按调用名索引的 {@code @Tool} Bean
     */
    public BuiltinSchemaSourceImpl(BuiltinToolBeans beans) {
        this.beans = beans;
    }

    /**
     * {@inheritDoc}
     * 重名冲突在索引里已被拿掉，这里同样不挑一个凑合。
     */
    @Override
    public Optional<String> jsonFor(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        String name = toolName.trim();
        return beans.find(name)
                .map(bean -> BuiltinToolSchemas.jsonOf(bean, name))
                .filter(schema -> schema != null && !schema.isBlank());
    }
}
