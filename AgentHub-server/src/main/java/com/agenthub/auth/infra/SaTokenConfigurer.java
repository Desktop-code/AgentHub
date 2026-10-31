package com.agenthub.auth.infra;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截。所属域：auth。
 * 约束：不注册 Spring Security 过滤器；除登录和健康检查外，/api 都要登录。
 * 浏览器跨域会先发 OPTIONS，预检没有 Token，不能当成未登录。
 * 滑动续期：checkLogin 通过后 renewTimeout，与 active-timeout 的 autoRenew 一起续命。
 */
@Configuration
public class SaTokenConfigurer implements WebMvcConfigurer {

    /**
     * 登录接口和健康检查匿名。文档路径也放开，生产应关掉 springdoc。
     *
     * @param registry MVC 拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            HttpServletRequest request = currentRequest();
            if (request != null && "OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return;
            }
            StpUtil.checkLogin();
            // 滑动续期：有鉴权请求则重置绝对 timeout（框架 autoRenew 只续 active-timeout）
            SaTokenSlidingRenew.renewAbsoluteTimeout();
        }))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**");
    }

    /**
     * @return 当前请求；非 Web 线程时为 null
     */
    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }
}
