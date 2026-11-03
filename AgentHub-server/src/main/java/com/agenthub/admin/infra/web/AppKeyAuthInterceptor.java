package com.agenthub.admin.infra.web;

import com.agenthub.admin.application.AppKeyAuthService;
import com.agenthub.admin.domain.model.AppIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * OpenAPI 应用鉴权拦截器。所属域：admin 的 infra（Web 适配）。
 * <p>作用面：只挂 {@code /api/open/**}，与 Sa-Token 用户会话互不影响。
 * <p>为什么用 request attribute 而不是 ThreadLocal：控制器用
 * {@code @RequestAttribute} 直接取身份，请求结束由容器回收，不会在线程池里串 app。
 * <p>复用约定：以后接 Agent 入站（{@code /api/open/agent/**}）只加控制器，不必再写一套校验。
 * <p>禁止：把请求头 secret 写日志 / 写 trace；本类不读 body。
 */
@Component
public class AppKeyAuthInterceptor implements HandlerInterceptor {

    /** 请求头：对外 appId */
    public static final String HEADER_APP_KEY = "X-App-Key";

    /** 请求头：应用密钥明文（仅传输） */
    public static final String HEADER_APP_SECRET = "X-App-Secret";

    /** request attribute 名，控制器用同名 {@code @RequestAttribute} 取 */
    public static final String ATTR_IDENTITY = "agenthubAppIdentity";

    private final AppKeyAuthService authService;

    /**
     * @param authService 应用密钥校验
     */
    public AppKeyAuthInterceptor(AppKeyAuthService authService) {
        this.authService = authService;
    }

    /**
     * 校验通过则把身份挂到 request 上；失败抛 BizException（401/403，由全局异常处理转中文）。
     *
     * @param request  当前请求
     * @param response 响应（本类不直接写）
     * @param handler  目标 handler
     * @return 恒为 true（失败已在异常里中断）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 浏览器 / 网关预检不带密钥，直接放行；真正的 POST 才做校验
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        AppIdentity identity = authService.authenticate(
                request.getHeader(HEADER_APP_KEY),
                request.getHeader(HEADER_APP_SECRET));
        request.setAttribute(ATTR_IDENTITY, identity);
        return true;
    }
}
