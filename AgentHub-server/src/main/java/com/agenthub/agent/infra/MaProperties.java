package com.agenthub.agent.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多智能体方案基础设施配置。所属域：agent。
 *
 * <p>当前只有租户口径：一期 sys_user 无 tenant_id，先用配置指定「当前租户」，
 * 列表与写操作都按它隔离；接真租户时替换 {@code PropertyMaTenantProvider} 即可。
 */
@ConfigurationProperties(prefix = "agenthub.agent")
public class MaProperties {

    /** 当前租户；0 会被实现兜底成 1，避免把方案写进保留租户 */
    private long currentTenantId = 1L;

    /**
     * @return 配置的当前租户
     */
    public long getCurrentTenantId() {
        return currentTenantId;
    }

    /**
     * @param currentTenantId 当前租户
     */
    public void setCurrentTenantId(long currentTenantId) {
        this.currentTenantId = currentTenantId;
    }
}
