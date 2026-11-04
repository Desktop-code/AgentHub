package com.agenthub.eval.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.repository.EvalResultRepository;
import com.agenthub.eval.infra.persistence.entity.EvalResultEntity;
import com.agenthub.eval.infra.persistence.mapper.EvalResultMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 评测结果仓储实现。所属域：eval 的 infra。Entity 不出本包。
 * <p>建跑时逐条 insert（单套件上限 500 条，量级小），换来「失败即整事务回滚」的简单语义，
 * 与刀1 {@code EvalCaseRepositoryImpl} 保持同一风格，也避开 MySQL 5.7 批量语句拼接。
 */
@Repository
public class EvalResultRepositoryImpl implements EvalResultRepository {

    private final EvalResultMapper mapper;

    /**
     * @param mapper eval_result
     */
    public EvalResultRepositoryImpl(EvalResultMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public int insertBatch(List<EvalResult> results, Long operatorId) {
        int written = 0;
        for (EvalResult one : results) {
            EvalResultEntity entity = new EvalResultEntity();
            entity.setTenantId(one.getTenantId());
            entity.setRunId(one.getRunId());
            entity.setCaseKey(one.getCaseKey());
            entity.setCaseId(one.getCaseId());
            entity.setSortOrder(one.getSortOrder() == null ? 0 : one.getSortOrder());
            // 占位行：判定与评分字段全部留空，由后续刀回填
            entity.setVerdict(null);
            entity.setRulePassed(null);
            entity.setOpenjudgeStatus(null);
            entity.setScoresJson(null);
            entity.setArtifactSummaryJson(null);
            entity.setSessionId(null);
            entity.setTraceId(null);
            entity.setCacheHit(null);
            entity.setPromptBreakdownJson(null);
            entity.setLatencyMs(null);
            entity.setAssertionDetailsJson(null);
            entity.setOpenjudgeRawJson(null);
            entity.setErrorMessage(null);
            entity.setStartedTime(one.getStartedTime());
            entity.setFinishedTime(one.getFinishedTime());
            mapper.insert(entity);
            written++;
        }
        return written;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalResult> findById(Long id) {
        EvalResultEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<EvalResult> listByRun(Long runId) {
        List<EvalResultEntity> rows = mapper.selectList(new LambdaQueryWrapper<EvalResultEntity>()
                .eq(EvalResultEntity::getRunId, runId)
                .orderByAsc(EvalResultEntity::getSortOrder)
                .orderByAsc(EvalResultEntity::getId));
        return rows.stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalResult> pageByVerdicts(long tenantId, List<EvalVerdict> verdicts,
                                                 long current, long size) {
        long pageNo = Math.max(current, 1);
        long pageSize = Math.min(Math.max(size, 1), 100);
        if (verdicts == null || verdicts.isEmpty()) {
            return PageResult.of(0, pageNo, pageSize, List.of());
        }
        List<String> names = verdicts.stream().map(EvalVerdict::name).toList();
        IPage<EvalResultEntity> page = mapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EvalResultEntity>()
                        .eq(EvalResultEntity::getTenantId, tenantId)
                        .in(EvalResultEntity::getVerdict, names)
                        .orderByDesc(EvalResultEntity::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalResult> pageByRun(Long runId, long tenantId, long current, long size) {
        long pageNo = Math.max(current, 1);
        long pageSize = Math.min(Math.max(size, 1), 100);
        IPage<EvalResultEntity> page = mapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EvalResultEntity>()
                        .eq(EvalResultEntity::getRunId, runId)
                        .eq(EvalResultEntity::getTenantId, tenantId)
                        .orderByAsc(EvalResultEntity::getSortOrder)
                        .orderByAsc(EvalResultEntity::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /**
     * {@inheritDoc}
     * <p>只允许 SKIPPED：本方法存在的唯一理由是取消收尾，
     * 若放开成任意判定，等于给了「一条 SQL 把整批打成 FAIL/PASS」的旁路，绕过规则门禁。
     */
    @Override
    public int markUnfinished(Long runId, long tenantId, EvalVerdict verdict, Long operatorId) {
        if (verdict != EvalVerdict.SKIPPED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能把未完成结果标记为 SKIPPED");
        }
        return mapper.update(null, new LambdaUpdateWrapper<EvalResultEntity>()
                .eq(EvalResultEntity::getRunId, runId)
                .eq(EvalResultEntity::getTenantId, tenantId)
                // IS NULL 而不是 = ''：占位行的 verdict 列就是 SQL NULL
                .isNull(EvalResultEntity::getVerdict)
                .set(EvalResultEntity::getVerdict, verdict.name())
                .set(EvalResultEntity::getFinishedTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public int updateOutcome(EvalResult result) {
        if (result == null || result.getId() == null) {
            return 0;
        }
        return mapper.update(null, new LambdaUpdateWrapper<EvalResultEntity>()
                .eq(EvalResultEntity::getId, result.getId())
                .eq(EvalResultEntity::getRunId, result.getRunId())
                .eq(EvalResultEntity::getTenantId, result.getTenantId())
                .set(EvalResultEntity::getVerdict, result.getVerdict() == null ? null : result.getVerdict().name())
                .set(EvalResultEntity::getRulePassed, result.getRulePassed())
                .set(EvalResultEntity::getOpenjudgeStatus, result.getOpenjudgeStatus())
                .set(EvalResultEntity::getScoresJson, result.getScoresJson())
                .set(EvalResultEntity::getArtifactSummaryJson, result.getArtifactSummaryJson())
                .set(EvalResultEntity::getSessionId, result.getSessionId())
                .set(EvalResultEntity::getTraceId, result.getTraceId())
                .set(EvalResultEntity::getCacheHit, result.getCacheHit())
                .set(EvalResultEntity::getPromptBreakdownJson, result.getPromptBreakdownJson())
                .set(EvalResultEntity::getLatencyMs, result.getLatencyMs())
                .set(EvalResultEntity::getAssertionDetailsJson, result.getAssertionDetailsJson())
                .set(EvalResultEntity::getOpenjudgeRawJson, result.getOpenjudgeRawJson())
                .set(EvalResultEntity::getErrorMessage, result.getErrorMessage())
                .set(EvalResultEntity::getStartedTime, result.getStartedTime())
                .set(EvalResultEntity::getFinishedTime, result.getFinishedTime()));
    }

    private EvalResult toDomain(EvalResultEntity entity) {
        EvalResult one = new EvalResult();
        one.setId(entity.getId());
        one.setTenantId(entity.getTenantId());
        one.setRunId(entity.getRunId());
        one.setCaseKey(entity.getCaseKey());
        one.setCaseId(entity.getCaseId());
        one.setSortOrder(entity.getSortOrder());
        one.setVerdict(EvalVerdict.parse(entity.getVerdict()));
        one.setRulePassed(entity.getRulePassed());
        one.setOpenjudgeStatus(entity.getOpenjudgeStatus());
        one.setScoresJson(entity.getScoresJson());
        one.setArtifactSummaryJson(entity.getArtifactSummaryJson());
        one.setSessionId(entity.getSessionId());
        one.setTraceId(entity.getTraceId());
        one.setCacheHit(entity.getCacheHit());
        one.setPromptBreakdownJson(entity.getPromptBreakdownJson());
        one.setLatencyMs(entity.getLatencyMs());
        one.setAssertionDetailsJson(entity.getAssertionDetailsJson());
        one.setOpenjudgeRawJson(entity.getOpenjudgeRawJson());
        one.setErrorMessage(entity.getErrorMessage());
        one.setStartedTime(entity.getStartedTime());
        one.setFinishedTime(entity.getFinishedTime());
        one.setCreateTime(entity.getCreateTime());
        one.setUpdateTime(entity.getUpdateTime());
        return one;
    }
}
