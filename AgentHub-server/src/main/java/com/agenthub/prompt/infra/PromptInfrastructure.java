package com.agenthub.prompt.infra;

import com.agenthub.prompt.infra.cache.PromptCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词域基础设施。所属域：prompt。
 * 只打开缓存参数绑定；Mapper / 仓储各自带注解，不在这里手工 new。
 */
@Configuration
@EnableConfigurationProperties(PromptCacheProperties.class)
public class PromptInfrastructure {
}
