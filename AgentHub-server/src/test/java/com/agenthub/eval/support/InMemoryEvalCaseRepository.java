package com.agenthub.eval.support;

import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.repository.EvalCaseRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版用例仓储（测试专用）。所属域：eval 的 test。
 * <p>刻意只提供「按版本读 + 追加版本行 + 墓碑化」，
 * 与真实实现一致地**不存在**原地改历史的入口，这样单测能证明升版语义。
 * 线程：非线程安全，仅单线程单测使用。
 */
public class InMemoryEvalCaseRepository implements EvalCaseRepository {

    private final Map<Long, EvalCase> rows = new LinkedHashMap<>();
    private long seq = 0;

    /** {@inheritDoc} */
    @Override
    public List<EvalCase> listByVersion(Long suiteId, int suiteVersion) {
        return rows.values().stream()
                .filter(one -> one.getSuiteId().equals(suiteId) && one.getSuiteVersion() == suiteVersion)
                .sorted(Comparator.comparing(EvalCase::getSortOrder).thenComparing(EvalCase::getId))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalCase> findByKey(Long suiteId, int suiteVersion, String caseKey) {
        return rows.values().stream()
                .filter(one -> one.getSuiteId().equals(suiteId)
                        && one.getSuiteVersion() == suiteVersion
                        && one.getCaseKey().equals(caseKey))
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public int insertBatch(List<EvalCase> cases, Long operatorId) {
        for (EvalCase one : new ArrayList<>(cases)) {
            one.setId(++seq);
            one.setUpdatedBy(operatorId);
            rows.put(one.getId(), one);
        }
        return cases.size();
    }

    /** {@inheritDoc} */
    @Override
    public long countByVersion(Long suiteId, int suiteVersion) {
        return rows.values().stream()
                .filter(one -> one.getSuiteId().equals(suiteId) && one.getSuiteVersion() == suiteVersion)
                .count();
    }

    /** {@inheritDoc} */
    @Override
    public int logicalDeleteBySuite(Long suiteId, Long operatorId) {
        List<Long> ids = rows.values().stream()
                .filter(one -> one.getSuiteId().equals(suiteId))
                .map(EvalCase::getId)
                .toList();
        ids.forEach(rows::remove);
        return ids.size();
    }

    /**
     * 统计某套件全部版本的行数，用于断言「旧版本没被抹掉」。
     *
     * @param suiteId 套件
     * @return 行数
     */
    public long countAllVersions(Long suiteId) {
        return rows.values().stream().filter(one -> one.getSuiteId().equals(suiteId)).count();
    }
}
