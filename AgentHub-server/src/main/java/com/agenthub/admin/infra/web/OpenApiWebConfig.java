package com.agenthub.admin.infra.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * OpenAPI 拦截器装配。所属域：admin 的 infra（Web 适配）。
 * <p>只覆盖 {@code /api/open/**}：这些路径已在 {@code SaTokenConfigurer} 里排除登录校验，
 * 由应用密钥拦截器取代；控制台 {@code /api/**} 仍走 Sa-Token。
 * <p>顺序设为最高优先：应用鉴权先失败先返回，避免未授权请求打到业务层。
 */
@Configuration
public class OpenApiWebConfig implements WebMvcConfigurer {

    private final AppKeyAuthInterceptor appKeyAuthInterceptor;

    /**
     * @param appKeyAuthInterceptor 应用密钥拦截器
     */
    public OpenApiWebConfig(AppKeyAuthInterceptor appKeyAuthInterceptor) {
        this.appKeyAuthInterceptor = appKeyAuthInterceptor;
    }

    /**
     * 注册应用鉴权，仅 OpenAPI 前缀。
     *
     * @param registry MVC 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(appKeyAuthInterceptor)
                .addPathPatterns("/api/open/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
