package com.agenthub.eval.infra;

import com.agenthub.eval.domain.service.EvalTenantProvider;
import org.springframework.stereotype.Component;

/**
 * 当前租户提供者（配置驱动）。所属域：eval 的 infra。
 * <p>一期 sys_user 无 tenant_id，Sa-Token 会话里也没有租户维度，
 * 因此先把租户收口到配置项；后续接真租户时只替换本实现，应用层不动。
 * <p>线程：无状态，可并发调用。
 */
@Component
public class PropertyEvalTenantProvider implements EvalTenantProvider {

    private final EvalProperties properties;

    /**
     * @param properties 评测域配置
     */
    public PropertyEvalTenantProvider(EvalProperties properties) {
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     * <p>配置值若被误设成 0，按「不可写租户」直接兜底成 1，避免把租户数据写进平台只读区。
     */
    @Override
    public long currentTenantId() {
        long configured = properties.getCurrentTenantId();
        return configured > 0 ? configured : 1L;
    }
}
