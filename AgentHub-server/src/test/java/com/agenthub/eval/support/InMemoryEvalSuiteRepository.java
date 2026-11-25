package com.agenthub.eval.support;

import com.agenthub.common.api.PageResult;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.model.EvalSuiteQuery;
import com.agenthub.eval.domain.repository.EvalSuiteRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版套件仓储（测试专用）。所属域：eval 的 test。
 * <p>只实现本刀用到的语义，重点是忠实复刻两件事：
 * <ul>
 *   <li>物理唯一键 (tenant_id, suite_key) 对**已逻辑删**的行同样生效；</li>
 *   <li>版本推进是 CAS：库里版本不等于期望值就返回 0。</li>
 * </ul>
 * 线程：非线程安全，仅单线程单测使用。
 */
public class InMemoryEvalSuiteRepository implements EvalSuiteRepository {

    private final Map<Long, EvalSuite> rows = new LinkedHashMap<>();
    /** 已逻辑删的 suiteKey（tenantId + key），模拟物理 UK 仍被占用 */
    private final List<String> tombstones = new ArrayList<>();
    private long seq = 0;

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalSuite> page(EvalSuiteQuery query) {
        List<EvalSuite> matched = rows.values().stream()
                .filter(one -> one.getTenantId() == query.getTenantId()
                        || (query.isIncludePlatform() && one.isPlatformOwned()))
                .filter(one -> query.getKeyword() == null || one.getSuiteKey().contains(query.getKeyword())
                        || one.getName().contains(query.getKeyword()))
                .toList();
        return PageResult.of(matched.size(), query.getCurrent(), query.getSize(), matched);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalSuite> findById(Long id) {
        return Optional.ofNullable(rows.get(id));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalSuite> findByTenantAndKey(long tenantId, String suiteKey) {
        return rows.values().stream()
                .filter(one -> one.getTenantId() == tenantId && one.getSuiteKey().equals(suiteKey))
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public long countByTenantAndKeyIncludingDeleted(long tenantId, String suiteKey) {
        long live = rows.values().stream()
                .filter(one -> one.getTenantId() == tenantId && one.getSuiteKey().equals(suiteKey))
                .count();
        long dead = tombstones.stream().filter(one -> one.equals(tenantId + "|" + suiteKey)).count();
        return live + dead;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(EvalSuite suite, Long operatorId) {
        suite.setId(++seq);
        suite.setCreateTime(LocalDateTime.now());
        suite.setUpdatedBy(operatorId);
        rows.put(suite.getId(), suite);
        return suite.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateMeta(EvalSuite suite, Long operatorId) {
        EvalSuite stored = rows.get(suite.getId());
        stored.setName(suite.getName());
        stored.setDescription(suite.getDescription());
        stored.setDefaultConfigJson(suite.getDefaultConfigJson());
        stored.setEnabled(suite.getEnabled());
        stored.setUpdatedBy(operatorId);
    }

    /** {@inheritDoc} */
    @Override
    public int bumpVersion(Long id, long tenantId, int expectedVersion, int newVersion, Long operatorId) {
        EvalSuite stored = rows.get(id);
        if (stored == null || stored.getTenantId() != tenantId
                || stored.getCurrentVersion() != expectedVersion) {
            return 0;
        }
        stored.setCurrentVersion(newVersion);
        stored.setUpdatedBy(operatorId);
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public int logicalDelete(Long id, long tenantId, Long operatorId) {
        EvalSuite stored = rows.get(id);
        if (stored == null || stored.getTenantId() != tenantId) {
            return 0;
        }
        rows.remove(id);
        tombstones.add(tenantId + "|" + stored.getSuiteKey());
        return 1;
    }

    /**
     * 预置一条平台只读样例，用于验证「平台行不可写」。
     *
     * @param suiteKey 逻辑键
     * @return 主键
     */
    public Long seedPlatformSuite(String suiteKey) {
        EvalSuite suite = new EvalSuite();
        suite.setTenantId(EvalSuite.PLATFORM_TENANT_ID);
        suite.setSuiteKey(suiteKey);
        suite.setName("平台样例");
        suite.setCurrentVersion(1);
        suite.setEnabled(EvalSuite.ENABLED);
        return insert(suite, 0L);
    }
}
