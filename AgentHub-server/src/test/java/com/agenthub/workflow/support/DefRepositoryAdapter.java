package com.agenthub.workflow.support;

import com.agenthub.common.api.PageResult;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDefQuery;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;

import java.util.List;
import java.util.Optional;

/**
 * 内存 WorkflowDef 仓储适配器。所属域：workflow 的 test support。
 * <p>窄测只依赖 findById（待办列表展示工作流名）；其余写操作不在本刀范围内，
 * 显式抛 UnsupportedOperationException，避免测试误用后静默通过。
 */
class DefRepositoryAdapter implements WorkflowDefRepository {

    private final InMemoryWorkflowStore store;

    DefRepositoryAdapter(InMemoryWorkflowStore store) {
        this.store = store;
    }

    @Override
    public PageResult<WorkflowDef> page(WorkflowDefQuery query) {
        // 只实现单测需要的过滤（租户 + 状态 + 分页），排序不做，避免过度模拟 SQL
        List<WorkflowDef> matched = store.listDefs().stream()
                .filter(def -> def.getTenantId() != null && def.getTenantId() == query.getTenantId())
                .filter(def -> query.getStatus() == null || query.getStatus().equals(def.getStatus()))
                .toList();
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        long current = Math.max(query.getCurrent(), 1);
        int from = (int) Math.min((current - 1) * size, matched.size());
        int to = (int) Math.min(from + size, matched.size());
        return PageResult.of(matched.size(), current, size, matched.subList(from, to));
    }

    @Override
    public Optional<WorkflowDef> findById(Long id) {
        return store.findDef(id);
    }

    @Override
    public Optional<WorkflowDef> findByTenantAndCode(long tenantId, String code) {
        throw new UnsupportedOperationException("单测不覆盖按编码查定义");
    }

    @Override
    public Long insert(WorkflowDef def, Long operatorId) {
        throw new UnsupportedOperationException("单测不覆盖定义新建");
    }

    @Override
    public void updateMeta(WorkflowDef def, Long operatorId) {
        throw new UnsupportedOperationException("单测不覆盖定义改名");
    }

    @Override
    public void markPublished(Long id, long tenantId, Long releaseId, Long operatorId) {
        throw new UnsupportedOperationException("单测不覆盖发布指针");
    }

    @Override
    public void touch(Long id, long tenantId, Long operatorId) {
        throw new UnsupportedOperationException("单测不覆盖定义 touch");
    }

    @Override
    public int softDelete(Long id, long tenantId, Long operatorId) {
        throw new UnsupportedOperationException("单测不覆盖定义删除");
    }

    /** {@inheritDoc}：内存实现按与 SQL 相同的口径过滤（已发布 + 有默认发布指针 + 说明非空）。 */
    @Override
    public List<WorkflowDef> listRoutable(long tenantId, int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        return store.listDefs().stream()
                .filter(def -> def.getTenantId() != null && def.getTenantId() == tenantId)
                .filter(def -> "HAS_RELEASE".equals(def.getStatus()) && def.getDefaultReleaseId() != null)
                .filter(def -> def.getDescription() != null && !def.getDescription().isBlank())
                .limit(size)
                .toList();
    }
}
