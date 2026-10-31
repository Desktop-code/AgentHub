package com.agenthub.memory.infra;

import com.agenthub.memory.domain.service.FactKeyNormalizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 fact_key 归一器。所属域：memory。
 * 别名来自 {@link MemoryProperties#getFactKeyAliases()}，可 YAML 扩展。
 */
@Configuration
public class FactKeyNormalizerConfig {

    /**
     * @param properties 记忆平台配置
     * @return 带内置+配置别名的归一器
     */
    @Bean
    public FactKeyNormalizer factKeyNormalizer(MemoryProperties properties) {
        return new FactKeyNormalizer(properties.getFactKeyAliases());
    }
}
