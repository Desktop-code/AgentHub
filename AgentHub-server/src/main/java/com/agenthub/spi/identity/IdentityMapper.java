package com.agenthub.spi.identity;

import java.util.Optional;

/**
 * 企业身份到平台用户的映射。所属域：spi。
 * <p>调用方：以后的 sidecar 认证过滤器。local 模式不调用。
 * 实现方：客户适配器，负责把 IdP 的 subject 对上 sys_user.id。
 * 线程：实现不得依赖请求线程之外的可变单例；平台本期不提供实现，也不注册假映射。
 */
public interface IdentityMapper {

    /**
     * 把外部主体换成平台用户。
     *
     * @param externalSubject 企业 IdP 的主体标识，例如 OIDC 的 sub
     * @return 平台用户 id；对不上时返回 empty，不要伪装成登录成功
     */
    Optional<Long> toPlatformUserId(String externalSubject);
}
