package com.agenthub.agent.infra;

import com.agenthub.agent.domain.service.MaTenantProvider;
import org.springframework.stereotype.Component;

/**
 * 当前租户提供者（配置驱动）。所属域：agent 的 infra。
 *
 * <p>一期 sys_user 无 tenant_id，Sa-Token 会话也没有租户维度；
 * 先把租户收口到配置项，接真租户时只替换本实现。
 * <p>线程：无状态，可并发调用。
 */
@Component
public class PropertyMaTenantProvider implements MaTenantProvider {

    private final MaProperties properties;

    /**
     * @param properties 多智能体方案配置
     */
    public PropertyMaTenantProvider(MaProperties properties) {
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     * <p>配置被误设成 0 时兜底成 1，避免把方案写进保留租户。
     */
    @Override
    public long currentTenantId() {
        long configured = properties.getCurrentTenantId();
        return configured > 0 ? configured : 1L;
    }
}
