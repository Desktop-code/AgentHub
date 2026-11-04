package com.agenthub.workflow.domain.service;

/**
 * 当前租户来源。所属域：workflow 的 domain。
 * <p>调用方：{@code WorkflowDefAppService}，每次读写都通过它取租户。
 * 实现方：infra 的 {@code PropertyWorkflowTenantProvider}（配置项
 * {@code agenthub.workflow.current-tenant-id}）。
 * <p>为什么不用 Sa-Token：一期 sys_user 无 tenant_id，会话里没有租户维度；
 * 这里留可替换的缝，接真租户时只换实现。
 * 线程：实现须无状态。
 */
public interface WorkflowTenantProvider {

    /**
     * @return 当前租户 id；不允许返回 0
     */
    long currentTenantId();
}
