package com.agenthub.eval.domain.service;

/**
 * 当前租户来源。所属域：eval 的 domain。
 * <p>调用方：{@code CaseStoreAppService}，每次读写都通过它取租户。
 * 实现方：infra 的 {@code PropertyEvalTenantProvider}（配置项 {@code agenthub.eval.current-tenant-id}）。
 * <p>为什么不用 Sa-Token：平台一期没有租户维度的登录上下文（sys_user 无 tenant_id），
 * 这里先留一个可替换的缝；接入真租户后再换成从 Token/SPI 读取，调用方无需改动。
 * 线程：实现须无状态。
 */
public interface EvalTenantProvider {

    /**
     * @return 当前租户 id；不允许返回 0（0 保留给平台只读样例）
     */
    long currentTenantId();
}
