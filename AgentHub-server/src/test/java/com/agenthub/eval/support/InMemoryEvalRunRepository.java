package com.agenthub.eval.support;

import com.agenthub.common.api.PageResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.repository.EvalRunRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版跑批仓储（测试专用）。所属域：eval 的 test。
 * <p>insert / findById 一律拷贝：避免应用层 mutate 领域对象时把「库内行」一并改掉，
 * 否则 CAS（PENDING→RUNNING）会因同一引用已变成 RUNNING 而误判抢跑失败。
 * 线程：非线程安全，仅单线程单测使用。
 */
public class InMemoryEvalRunRepository implements EvalRunRepository {

    private final Map<Long, EvalRun> rows = new LinkedHashMap<>();
    private long seq = 0;

    /** {@inheritDoc} */
    @Override
    public Long insert(EvalRun run, Long operatorId) {
        run.setId(++seq);
        run.setCreatedBy(operatorId);
        run.setUpdatedBy(operatorId);
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        run.setPassed(run.getPassed() == null ? 0 : run.getPassed());
        run.setFailed(run.getFailed() == null ? 0 : run.getFailed());
        run.setErrored(run.getErrored() == null ? 0 : run.getErrored());
        run.setSkipped(run.getSkipped() == null ? 0 : run.getSkipped());
        rows.put(run.getId(), copyOf(run));
        return run.getId();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalRun> findById(Long id) {
        EvalRun stored = rows.get(id);
        return stored == null ? Optional.empty() : Optional.of(copyOf(stored));
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalRun> page(long tenantId, Long suiteId, Long agentId, String status,
                                    long current, long size) {
        List<EvalRun> matched = rows.values().stream()
                .filter(one -> one.getTenantId() == tenantId)
                .filter(one -> suiteId == null || suiteId.equals(one.getSuiteId()))
                .filter(one -> agentId == null || agentId.equals(one.getAgentId()))
                .filter(one -> status == null || status.isBlank()
                        || (one.getStatus() != null && one.getStatus().name().equalsIgnoreCase(status.trim())))
                .sorted(Comparator.comparing(EvalRun::getId).reversed())
                .map(this::copyOf)
                .toList();
        long pageSize = Math.min(Math.max(size, 1), 100);
        long pageNo = Math.max(current, 1);
        int from = (int) Math.min((pageNo - 1) * pageSize, matched.size());
        int to = (int) Math.min(from + pageSize, matched.size());
        return PageResult.of(matched.size(), pageNo, pageSize, new ArrayList<>(matched.subList(from, to)));
    }

    /** {@inheritDoc} */
    @Override
    public int updateState(EvalRun run, Long operatorId) {
        EvalRun stored = rows.get(run.getId());
        if (stored == null || !stored.getTenantId().equals(run.getTenantId())) {
            return 0;
        }
        applyState(stored, run, operatorId);
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public int casUpdateState(EvalRun run, EvalRunStatus expected, Long operatorId) {
        EvalRun stored = rows.get(run.getId());
        if (stored == null || !stored.getTenantId().equals(run.getTenantId())
                || stored.getStatus() != expected) {
            return 0;
        }
        applyState(stored, run, operatorId);
        return 1;
    }

    private void applyState(EvalRun stored, EvalRun run, Long operatorId) {
        stored.setStatus(run.getStatus());
        stored.setGatePassed(run.getGatePassed());
        stored.setTotalCases(run.getTotalCases());
        stored.setPassed(run.getPassed());
        stored.setFailed(run.getFailed());
        stored.setErrored(run.getErrored());
        stored.setSkipped(run.getSkipped());
        stored.setStartedTime(run.getStartedTime());
        stored.setFinishedTime(run.getFinishedTime());
        stored.setErrorMessage(run.getErrorMessage());
        stored.setReportJson(run.getReportJson());
        stored.setReportPath(run.getReportPath());
        if (run.getAgentSnapshotHash() != null) {
            stored.setAgentSnapshotHash(run.getAgentSnapshotHash());
        }
        stored.setUpdatedBy(operatorId);
        stored.setUpdateTime(LocalDateTime.now());
    }

    private EvalRun copyOf(EvalRun src) {
        EvalRun copy = new EvalRun();
        copy.setId(src.getId());
        copy.setTenantId(src.getTenantId());
        copy.setSuiteId(src.getSuiteId());
        copy.setSuiteVersion(src.getSuiteVersion());
        copy.setAgentId(src.getAgentId());
        copy.setAgentSnapshotHash(src.getAgentSnapshotHash());
        copy.setStatus(src.getStatus());
        copy.setGatePassed(src.getGatePassed());
        copy.setTotalCases(src.getTotalCases());
        copy.setPassed(src.getPassed());
        copy.setFailed(src.getFailed());
        copy.setErrored(src.getErrored());
        copy.setSkipped(src.getSkipped());
        copy.setOpenjudgeEnabled(src.getOpenjudgeEnabled());
        copy.setConfigJson(src.getConfigJson());
        copy.setReportPath(src.getReportPath());
        copy.setReportJson(src.getReportJson());
        copy.setTriggerUserId(src.getTriggerUserId());
        copy.setErrorMessage(src.getErrorMessage());
        copy.setStartedTime(src.getStartedTime());
        copy.setFinishedTime(src.getFinishedTime());
        copy.setCreatedBy(src.getCreatedBy());
        copy.setCreateTime(src.getCreateTime());
        copy.setUpdatedBy(src.getUpdatedBy());
        copy.setUpdateTime(src.getUpdateTime());
        return copy;
    }
}
