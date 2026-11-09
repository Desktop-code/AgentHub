package com.agenthub.auth.infra;

import com.agenthub.auth.infra.cache.AclCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 鉴权域基础设施。所属域：auth。
 * 只打开缓存参数绑定；Sa-Token 与仓储各自带注解，不在这里手工 new。
 */
@Configuration
@EnableConfigurationProperties(AclCacheProperties.class)
public class AuthCacheInfrastructure {
}
