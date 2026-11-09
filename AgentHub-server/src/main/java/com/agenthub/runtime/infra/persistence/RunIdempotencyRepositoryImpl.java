package com.agenthub.runtime.infra.persistence;

import com.agenthub.runtime.domain.model.TrialIdempotencyRecord;
import com.agenthub.runtime.domain.repository.RunIdempotencyRepository;
import com.agenthub.runtime.infra.persistence.entity.RunIdempotencyEntity;
import com.agenthub.runtime.infra.persistence.mapper.RunIdempotencyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 发消息幂等记录的持久化实现。所属域：runtime 的 infra。
 * <p>
 * 唯一冲突在**本层**翻译成 {@link #insert} 返回 false：应用层不应感知 Spring DAO 异常，
 * 更不能把「同键第二次提交」当成 5xx。
 * <p>
 * 时间列：{@code create_time} / {@code update_time} 由库负责（DEFAULT / ON UPDATE），
 * 但状态回写与接管会显式写 {@code update_time}——同值 UPDATE 时 MySQL 的 ON UPDATE 不会触发，
 * 而陈旧判定恰好依赖这一列，必须确保每次刷新都推进它。
 */
@Repository
public class RunIdempotencyRepositoryImpl implements RunIdempotencyRepository {

    private final RunIdempotencyMapper mapper;

    /**
     * @param mapper agent_run_idempotency
     */
    public RunIdempotencyRepositoryImpl(RunIdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TrialIdempotencyRecord> find(Long sessionId, String idemKey) {
        if (sessionId == null || idemKey == null || idemKey.isBlank()) {
            return Optional.empty();
        }
        RunIdempotencyEntity entity = mapper.selectOne(new LambdaQueryWrapper<RunIdempotencyEntity>()
                .eq(RunIdempotencyEntity::getSessionId, sessionId)
                .eq(RunIdempotencyEntity::getIdemKey, idemKey)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public boolean insert(TrialIdempotencyRecord record) {
        RunIdempotencyEntity entity = new RunIdempotencyEntity();
        entity.setSessionId(record.getSessionId());
        entity.setIdemKey(record.getIdemKey());
        entity.setFingerprint(record.getFingerprint());
        entity.setState(record.getState().name());
        entity.setPlanPending(record.isPlanPending() ? 1 : 0);
        try {
            mapper.insert(entity);
            return true;
        } catch (DuplicateKeyException ex) {
            // 唯一键 (session_id, idem_key) 已存在：并发/重复提交，交给上层读回处理
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public int updateResult(TrialIdempotencyRecord record) {
        return mapper.update(null, new LambdaUpdateWrapper<RunIdempotencyEntity>()
                .eq(RunIdempotencyEntity::getSessionId, record.getSessionId())
                .eq(RunIdempotencyEntity::getIdemKey, record.getIdemKey())
                .set(RunIdempotencyEntity::getState, record.getState().name())
                .set(RunIdempotencyEntity::getRunStatus, record.getRunStatus())
                .set(RunIdempotencyEntity::getAssistantPreview, record.getAssistantPreview())
                .set(RunIdempotencyEntity::getErrorSummary, record.getErrorSummary())
                .set(RunIdempotencyEntity::getPlanPending, record.isPlanPending() ? 1 : 0)
                .set(RunIdempotencyEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public boolean takeOver(TrialIdempotencyRecord record) {
        int rows = mapper.update(null, new LambdaUpdateWrapper<RunIdempotencyEntity>()
                .eq(RunIdempotencyEntity::getSessionId, record.getSessionId())
                .eq(RunIdempotencyEntity::getIdemKey, record.getIdemKey())
                // 只接管仍是 RUNNING 的行：终态一旦落库就绝不回退，否则等于把已完成的轮次重跑
                .eq(RunIdempotencyEntity::getState, TrialIdempotencyRecord.State.RUNNING.name())
                .set(RunIdempotencyEntity::getFingerprint, record.getFingerprint())
                .set(RunIdempotencyEntity::getRunStatus, null)
                .set(RunIdempotencyEntity::getAssistantPreview, null)
                .set(RunIdempotencyEntity::getErrorSummary, null)
                .set(RunIdempotencyEntity::getPlanPending, 0)
                .set(RunIdempotencyEntity::getUpdateTime, LocalDateTime.now()));
        return rows > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void delete(Long sessionId, String idemKey) {
        if (sessionId == null || idemKey == null || idemKey.isBlank()) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<RunIdempotencyEntity>()
                .eq(RunIdempotencyEntity::getSessionId, sessionId)
                .eq(RunIdempotencyEntity::getIdemKey, idemKey));
    }

    /**
     * 实体 → 领域。
     *
     * @param entity 行
     * @return 领域记录
     */
    private TrialIdempotencyRecord toDomain(RunIdempotencyEntity entity) {
        TrialIdempotencyRecord record = new TrialIdempotencyRecord();
        record.setSessionId(entity.getSessionId());
        record.setIdemKey(entity.getIdemKey());
        record.setFingerprint(entity.getFingerprint());
        record.setState(parseState(entity.getState()));
        record.setRunStatus(entity.getRunStatus());
        record.setAssistantPreview(entity.getAssistantPreview());
        record.setErrorSummary(entity.getErrorSummary());
        record.setPlanPending(entity.getPlanPending() != null && entity.getPlanPending() == 1);
        record.setUpdatedAtMs(epochMs(entity.getUpdateTime()));
        return record;
    }

    /**
     * 未知状态按 RUNNING 处理：宁可拒绝一次重跑，也不能把一条含义不明的行当成成功去重放。
     *
     * @param raw 库值
     * @return 状态
     */
    private TrialIdempotencyRecord.State parseState(String raw) {
        if (TrialIdempotencyRecord.State.SUCCESS.name().equals(raw)) {
            return TrialIdempotencyRecord.State.SUCCESS;
        }
        if (TrialIdempotencyRecord.State.FAILED.name().equals(raw)) {
            return TrialIdempotencyRecord.State.FAILED;
        }
        return TrialIdempotencyRecord.State.RUNNING;
    }

    /**
     * @param time 库里的 update_time，可空
     * @return epoch 毫秒；空视为 0（会被判定为陈旧，可被接管）
     */
    private long epochMs(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
