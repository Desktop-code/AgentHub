package com.agenthub.eval.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.repository.EvalRunRepository;
import com.agenthub.eval.infra.persistence.entity.EvalRunEntity;
import com.agenthub.eval.infra.persistence.mapper.EvalRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 评测运行仓储实现。所属域：eval 的 infra。Entity 不出本包。
 * <p>写入策略：状态与计数器**永远**用 {@link #updateState} 一次写全（不提供只改某一列的方法），
 * 这样应用层只能通过状态机算好一整套结果再落库，不会出现「状态前进了但计数没跟」的中间态。
 * <p>租户：按 id 读不带条件（应用层据此区分 404 与越权）；写带 tenant_id 做二次保险。
 */
@Repository
public class EvalRunRepositoryImpl implements EvalRunRepository {

    private final EvalRunMapper mapper;

    /**
     * @param mapper eval_run
     */
    public EvalRunRepositoryImpl(EvalRunMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(EvalRun run, Long operatorId) {
        EvalRunEntity entity = new EvalRunEntity();
        entity.setTenantId(run.getTenantId());
        entity.setSuiteId(run.getSuiteId());
        entity.setSuiteVersion(run.getSuiteVersion());
        entity.setAgentId(run.getAgentId());
        entity.setAgentSnapshotHash(run.getAgentSnapshotHash());
        entity.setStatus(run.getStatus() == null ? EvalRunStatus.PENDING.name() : run.getStatus().name());
        entity.setGatePassed(run.getGatePassed());
        entity.setTotalCases(run.getTotalCases() == null ? 0 : run.getTotalCases());
        // 建跑时四个计数器恒为 0：占位结果行还没有任何判定，先显式写 0 避免 null 与 0 两种「空」
        entity.setPassed(0);
        entity.setFailed(0);
        entity.setErrored(0);
        entity.setSkipped(0);
        entity.setOpenjudgeEnabled(run.getOpenjudgeEnabled() == null ? 0 : run.getOpenjudgeEnabled());
        entity.setConfigJson(run.getConfigJson());
        entity.setReportPath(run.getReportPath());
        entity.setReportJson(run.getReportJson());
        entity.setTriggerUserId(run.getTriggerUserId());
        entity.setErrorMessage(run.getErrorMessage());
        entity.setStartedTime(run.getStartedTime());
        entity.setFinishedTime(run.getFinishedTime());
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalRun> findById(Long id) {
        EvalRunEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalRun> page(long tenantId, Long suiteId, Long agentId, String status,
                                    long current, long size) {
        long pageNo = PageQuery.clampCurrent(current);
        long pageSize = PageQuery.clampSize(size);
        LambdaQueryWrapper<EvalRunEntity> wrapper = new LambdaQueryWrapper<EvalRunEntity>()
                .eq(EvalRunEntity::getTenantId, tenantId)
                .eq(suiteId != null, EvalRunEntity::getSuiteId, suiteId)
                .eq(agentId != null, EvalRunEntity::getAgentId, agentId)
                .eq(status != null && !status.isBlank(), EvalRunEntity::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .orderByDesc(EvalRunEntity::getId);
        IPage<EvalRunEntity> page = mapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /**
     * {@inheritDoc}
     * <p>为什么连 agent_snapshot_hash 一起写：它只能在「装配/开跑那一刻」确定，
     * 与状态推进是同一时机；分开写会留下 hash 与 status 不一致的窗口。
     */
    @Override
    public int updateState(EvalRun run, Long operatorId) {
        LambdaUpdateWrapper<EvalRunEntity> wrapper = new LambdaUpdateWrapper<EvalRunEntity>()
                .eq(EvalRunEntity::getId, run.getId())
                .eq(EvalRunEntity::getTenantId, run.getTenantId())
                .set(EvalRunEntity::getStatus, run.getStatus() == null ? null : run.getStatus().name())
                .set(EvalRunEntity::getGatePassed, run.getGatePassed())
                .set(EvalRunEntity::getTotalCases, run.getTotalCases())
                .set(EvalRunEntity::getPassed, run.getPassed())
                .set(EvalRunEntity::getFailed, run.getFailed())
                .set(EvalRunEntity::getErrored, run.getErrored())
                .set(EvalRunEntity::getSkipped, run.getSkipped())
                .set(EvalRunEntity::getStartedTime, run.getStartedTime())
                .set(EvalRunEntity::getFinishedTime, run.getFinishedTime())
                .set(EvalRunEntity::getErrorMessage, run.getErrorMessage())
                .set(EvalRunEntity::getReportJson, run.getReportJson())
                .set(EvalRunEntity::getReportPath, run.getReportPath())
                .set(EvalRunEntity::getUpdateBy, operatorId);
        // 空 hash 不覆盖已有值：刀2b 写入真指纹后，后续状态推进不该把它抹回 null
        if (run.getAgentSnapshotHash() != null) {
            wrapper.set(EvalRunEntity::getAgentSnapshotHash, run.getAgentSnapshotHash());
        }
        return mapper.update(null, wrapper);
    }

    /**
     * {@inheritDoc}
     * <p>在 {@link #updateState} 条件上再加 status=expected，形成开跑 CAS。
     */
    @Override
    public int casUpdateState(EvalRun run, EvalRunStatus expected, Long operatorId) {
        if (expected == null) {
            return 0;
        }
        LambdaUpdateWrapper<EvalRunEntity> wrapper = new LambdaUpdateWrapper<EvalRunEntity>()
                .eq(EvalRunEntity::getId, run.getId())
                .eq(EvalRunEntity::getTenantId, run.getTenantId())
                .eq(EvalRunEntity::getStatus, expected.name())
                .set(EvalRunEntity::getStatus, run.getStatus() == null ? null : run.getStatus().name())
                .set(EvalRunEntity::getGatePassed, run.getGatePassed())
                .set(EvalRunEntity::getTotalCases, run.getTotalCases())
                .set(EvalRunEntity::getPassed, run.getPassed())
                .set(EvalRunEntity::getFailed, run.getFailed())
                .set(EvalRunEntity::getErrored, run.getErrored())
                .set(EvalRunEntity::getSkipped, run.getSkipped())
                .set(EvalRunEntity::getStartedTime, run.getStartedTime())
                .set(EvalRunEntity::getFinishedTime, run.getFinishedTime())
                .set(EvalRunEntity::getErrorMessage, run.getErrorMessage())
                .set(EvalRunEntity::getReportJson, run.getReportJson())
                .set(EvalRunEntity::getReportPath, run.getReportPath())
                .set(EvalRunEntity::getUpdateBy, operatorId);
        if (run.getAgentSnapshotHash() != null) {
            wrapper.set(EvalRunEntity::getAgentSnapshotHash, run.getAgentSnapshotHash());
        }
        return mapper.update(null, wrapper);
    }

    private EvalRun toDomain(EvalRunEntity entity) {
        EvalRun run = new EvalRun();
        run.setId(entity.getId());
        run.setTenantId(entity.getTenantId());
        run.setSuiteId(entity.getSuiteId());
        run.setSuiteVersion(entity.getSuiteVersion());
        run.setAgentId(entity.getAgentId());
        run.setAgentSnapshotHash(entity.getAgentSnapshotHash());
        // 脏状态值走 parse 直接抛错，不静默当 PENDING：否则会把「库被手改」伪装成正常待跑
        run.setStatus(EvalRunStatus.parse(entity.getStatus()));
        run.setGatePassed(entity.getGatePassed());
        run.setTotalCases(entity.getTotalCases());
        run.setPassed(entity.getPassed());
        run.setFailed(entity.getFailed());
        run.setErrored(entity.getErrored());
        run.setSkipped(entity.getSkipped());
        run.setOpenjudgeEnabled(entity.getOpenjudgeEnabled());
        run.setConfigJson(entity.getConfigJson());
        run.setReportPath(entity.getReportPath());
        run.setReportJson(entity.getReportJson());
        run.setTriggerUserId(entity.getTriggerUserId());
        run.setErrorMessage(entity.getErrorMessage());
        run.setStartedTime(entity.getStartedTime());
        run.setFinishedTime(entity.getFinishedTime());
        run.setCreatedBy(entity.getCreateBy());
        run.setCreateTime(entity.getCreateTime());
        run.setUpdatedBy(entity.getUpdateBy());
        run.setUpdateTime(entity.getUpdateTime());
        return run;
    }
}
