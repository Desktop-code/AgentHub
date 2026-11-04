package com.agenthub.workflow.infra;

import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import org.springframework.stereotype.Component;

/**
 * 当前租户提供者（配置驱动）。所属域：workflow 的 infra。
 * <p>一期 sys_user 无 tenant_id，Sa-Token 会话也没有租户维度；
 * 先把租户收口到配置项，接真租户时只替换本实现。
 * <p>线程：无状态，可并发调用。
 */
@Component
public class PropertyWorkflowTenantProvider implements WorkflowTenantProvider {

    private final WorkflowProperties properties;

    /**
     * @param properties 工作流域配置
     */
    public PropertyWorkflowTenantProvider(WorkflowProperties properties) {
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     * <p>配置被误设成 0 时兜底成 1，避免把工作流写进保留租户。
     */
    @Override
    public long currentTenantId() {
        long configured = properties.getCurrentTenantId();
        return configured > 0 ? configured : 1L;
    }
}
