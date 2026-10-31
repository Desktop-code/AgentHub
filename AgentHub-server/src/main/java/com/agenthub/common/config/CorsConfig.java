package com.agenthub.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 开发期跨域。所属域：common。
 * 约束：与前端 axios 一样不带 cookie，只放行配置里的 Origin，不用 *。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final AgentHubProperties properties;

    /**
     * @param properties 含 allowed-origins
     */
    public CorsConfig(AgentHubProperties properties) {
        this.properties = properties;
    }

    /**
     * 只放开 /api。凭证关闭，Token 走 Authorization 头。
     *
     * @param registry MVC 跨域注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = properties.getCors().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
