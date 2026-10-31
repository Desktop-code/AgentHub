package com.agenthub.common.web;

/**
 * 当前操作者与请求来源。所属域：common。
 * <p>约束：业务代码通过本接口取登录人和 IP，不直接散落 Sa-Token 调用（鉴权注解除外）。
 * 实现放在 auth，未登录时用户相关方法返回 null，不抛异常。
 */
public interface OperatorContext {

    /**
     * @return 当前登录用户 id；未登录时为 null
     */
    Long userIdOrNull();

    /**
     * @return 当前登录用户 id
     * @throws com.agenthub.common.error.BizException 未登录时抛出 UNAUTHORIZED
     */
    long requireUserId();

    /**
     * @return 登录名；未登录或会话里没有时为 null
     */
    String usernameOrNull();

    /**
     * @return 客户端 IP；无 Web 请求时为空串
     */
    String clientIp();

    /**
     * @return User-Agent；无请求时为空串
     */
    String userAgent();

    /**
     * @return HTTP 方法；无请求时为 null
     */
    String requestMethod();

    /**
     * @return 请求路径（不含 query）；无请求时为 null
     */
    String requestUri();
}
