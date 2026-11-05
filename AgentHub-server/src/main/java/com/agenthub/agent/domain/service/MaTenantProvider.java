package com.agenthub.agent.domain.service;

/**
 * 多智能体方案的当前租户来源。所属域：agent。
 *
 * <p>调用方：{@link com.agenthub.agent.application.MaDefAppService} 与
 * {@code com.agenthub.runtime} 的 ma 读源。
 * 实现方：infra 的 {@code PropertyMaTenantProvider}（配置项
 * {@code agenthub.agent.current-tenant-id}）。
 *
 * <p>为什么不用 Sa-Token：一期 sys_user 无 tenant_id，会话里没有租户维度；
 * 先留可替换的缝，真正接入租户时只换实现。
 * <p>线程：实现须无状态。
 */
public interface MaTenantProvider {

    /**
     * @return 当前租户 id；不允许返回 0
     */
    long currentTenantId();
}
