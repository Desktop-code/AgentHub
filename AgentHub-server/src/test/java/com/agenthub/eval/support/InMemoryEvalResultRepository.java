package com.agenthub.eval.support;

import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.repository.EvalResultRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内存版结果仓储（测试专用）。所属域：eval 的 test。
 * <p>忠实复刻真实实现的两处关键口径：
 * <ul>
 *   <li>{@code markUnfinished} 只改 verdict IS NULL 的行，且只接受 SKIPPED；</li>
 *   <li>分页按 sort_order、id 升序，与库内 ORDER BY 一致。</li>
 * </ul>
 * 线程：非线程安全，仅单线程单测使用。
 */
public class InMemoryEvalResultRepository implements EvalResultRepository {

    private final Map<Long, EvalResult> rows = new LinkedHashMap<>();
    private long seq = 0;

    /** {@inheritDoc} */
    @Override
    public int insertBatch(List<EvalResult> results, Long operatorId) {
        for (EvalResult one : new ArrayList<>(results)) {
            one.setId(++seq);
            one.setVerdict(null);
            one.setCreateTime(LocalDateTime.now());
            rows.put(one.getId(), one);
        }
        return results.size();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalResult> findById(Long id) {
        EvalResult one = rows.get(id);
        return one == null ? Optional.empty() : Optional.of(one);
    }

    /** {@inheritDoc} */
    @Override
    public List<EvalResult> listByRun(Long runId) {
        return rows.values().stream()
                .filter(one -> one.getRunId().equals(runId))
                .sorted((a, b) -> {
                    int bySort = Integer.compare(a.getSortOrder() == null ? 0 : a.getSortOrder(),
                            b.getSortOrder() == null ? 0 : b.getSortOrder());
                    return bySort != 0 ? bySort : Long.compare(a.getId(), b.getId());
                })
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalResult> pageByVerdicts(long tenantId, List<EvalVerdict> verdicts,
                                                 long current, long size) {
        Set<EvalVerdict> allow = verdicts == null ? Set.of()
                : verdicts.stream().collect(Collectors.toSet());
        List<EvalResult> matched = rows.values().stream()
                .filter(one -> one.getTenantId() == tenantId)
                .filter(one -> one.getVerdict() != null && allow.contains(one.getVerdict()))
                .sorted(Comparator.comparing(EvalResult::getId).reversed())
                .toList();
        long pageSize = Math.min(Math.max(size, 1), 100);
        long pageNo = Math.max(current, 1);
        int from = (int) Math.min((pageNo - 1) * pageSize, matched.size());
        int to = (int) Math.min(from + pageSize, matched.size());
        return PageResult.of(matched.size(), pageNo, pageSize, new ArrayList<>(matched.subList(from, to)));
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalResult> pageByRun(Long runId, long tenantId, long current, long size) {
        List<EvalResult> matched = rows.values().stream()
                .filter(one -> one.getRunId().equals(runId) && one.getTenantId() == tenantId)
                .sorted((a, b) -> Integer.compare(a.getSortOrder() == null ? 0 : a.getSortOrder(),
                        b.getSortOrder() == null ? 0 : b.getSortOrder()))
                .toList();
        long pageSize = Math.min(Math.max(size, 1), 100);
        long pageNo = Math.max(current, 1);
        int from = (int) Math.min((pageNo - 1) * pageSize, matched.size());
        int to = (int) Math.min(from + pageSize, matched.size());
        return PageResult.of(matched.size(), pageNo, pageSize, new ArrayList<>(matched.subList(from, to)));
    }

    /**
     * {@inheritDoc}
     * <p>与真实实现同口径：拒绝非 SKIPPED，避免测试放宽了「只能跳过不能批量判失败」的约束。
     */
    @Override
    public int markUnfinished(Long runId, long tenantId, EvalVerdict verdict, Long operatorId) {
        if (verdict != EvalVerdict.SKIPPED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能把未完成结果标记为 SKIPPED");
        }
        int touched = 0;
        for (EvalResult one : rows.values()) {
            if (one.getRunId().equals(runId) && one.getTenantId() == tenantId && one.isUnfinished()) {
                one.setVerdict(EvalVerdict.SKIPPED);
                one.setFinishedTime(LocalDateTime.now());
                touched++;
            }
        }
        return touched;
    }

    /** {@inheritDoc} */
    @Override
    public int updateOutcome(EvalResult result) {
        EvalResult stored = rows.get(result.getId());
        if (stored == null || !stored.getRunId().equals(result.getRunId())
                || !stored.getTenantId().equals(result.getTenantId())) {
            return 0;
        }
        stored.setVerdict(result.getVerdict());
        stored.setRulePassed(result.getRulePassed());
        stored.setOpenjudgeStatus(result.getOpenjudgeStatus());
        stored.setScoresJson(result.getScoresJson());
        stored.setArtifactSummaryJson(result.getArtifactSummaryJson());
        stored.setSessionId(result.getSessionId());
        stored.setTraceId(result.getTraceId());
        stored.setCacheHit(result.getCacheHit());
        stored.setPromptBreakdownJson(result.getPromptBreakdownJson());
        stored.setLatencyMs(result.getLatencyMs());
        stored.setAssertionDetailsJson(result.getAssertionDetailsJson());
        stored.setOpenjudgeRawJson(result.getOpenjudgeRawJson());
        stored.setErrorMessage(result.getErrorMessage());
        stored.setStartedTime(result.getStartedTime());
        stored.setFinishedTime(result.getFinishedTime());
        return 1;
    }

    /**
     * 直接改某一行判定，用于构造「取消前已有部分 PASS/FAIL」的场景。
     *
     * @param runId   跑批
     * @param caseKey 用例键
     * @param verdict 目标判定
     */
    public void forceVerdict(Long runId, String caseKey, EvalVerdict verdict) {
        rows.values().stream()
                .filter(one -> one.getRunId().equals(runId) && one.getCaseKey().equals(caseKey))
                .findFirst()
                .orElseThrow()
                .setVerdict(verdict);
    }

    /**
     * 统计某跑批的行数。
     *
     * @param runId 跑批
     * @return 行数
     */
    public long countByRun(Long runId) {
        return rows.values().stream().filter(one -> one.getRunId().equals(runId)).count();
    }
}
