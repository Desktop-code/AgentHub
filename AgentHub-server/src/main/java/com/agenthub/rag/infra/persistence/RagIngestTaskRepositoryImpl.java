package com.agenthub.rag.infra.persistence;

import com.agenthub.rag.domain.model.RagIngestStatus;
import com.agenthub.rag.domain.model.RagIngestTask;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.infra.persistence.entity.RagIngestTaskEntity;
import com.agenthub.rag.infra.persistence.mapper.RagIngestTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 入库任务仓储实现。所属域：rag 的 infra。
 * 状态推进集中在这里，应用服务只表达「进入下一阶段」，不直接拼 SQL。
 */
@Repository
public class RagIngestTaskRepositoryImpl implements RagIngestTaskRepository {

    private final RagIngestTaskMapper mapper;

    /**
     * @param mapper rag_ingest_task
     */
    public RagIngestTaskRepositoryImpl(RagIngestTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(RagIngestTask task, Long operatorId) {
        RagIngestTaskEntity entity = new RagIngestTaskEntity();
        entity.setTenantId(task.getTenantId() == null ? 0L : task.getTenantId());
        entity.setKbId(task.getKbId());
        entity.setDocId(task.getDocId());
        entity.setStatus(task.getStatus() == null ? RagIngestStatus.PENDING.getCode() : task.getStatus());
        entity.setStage(task.getStage());
        entity.setProgress(task.getProgress() == null ? 0 : task.getProgress());
        entity.setAttempts(task.getAttempts() == null ? 0 : task.getAttempts());
        entity.setErrorMsg(task.getErrorMsg());
        // 全局 logic-delete-field=deleted：插入必须显式 0，避免 null 导致后续 UPDATE/SELECT 带 deleted=0 命中 0 行
        entity.setDeleted(0);
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RagIngestTask> findById(Long id) {
        RagIngestTaskEntity entity = id == null ? null : mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RagIngestTask> findByDocId(Long docId) {
        if (docId == null) {
            return Optional.empty();
        }
        RagIngestTaskEntity entity = mapper.selectOne(new LambdaQueryWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getDocId, docId)
                .orderByDesc(RagIngestTaskEntity::getId)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * {@inheritDoc}
     * 用 setSql 让 attempts 自增，避免「读-改-写」在并发重试下丢计数。
     */
    @Override
    public void markRunning(Long id) {
        // 已进入工作线程：阶段用「解析」而非「排队中」，避免 UI 长期显示排队像无人消费
        mapper.update(null, new LambdaUpdateWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getId, id)
                .set(RagIngestTaskEntity::getStatus, RagIngestStatus.RUNNING.getCode())
                .set(RagIngestTaskEntity::getStage, "解析")
                .set(RagIngestTaskEntity::getProgress, 5)
                .set(RagIngestTaskEntity::getErrorMsg, null)
                .set(RagIngestTaskEntity::getStartTime, LocalDateTime.now())
                .set(RagIngestTaskEntity::getFinishTime, null)
                .setSql("attempts = attempts + 1"));
    }

    /** {@inheritDoc} */
    @Override
    public void updateProgress(Long id, String stage, int progress) {
        mapper.update(null, new LambdaUpdateWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getId, id)
                .set(RagIngestTaskEntity::getStage, stage)
                .set(RagIngestTaskEntity::getProgress, Math.min(Math.max(progress, 0), 100)));
    }

    /** {@inheritDoc} */
    @Override
    public void markSuccess(Long id) {
        mapper.update(null, new LambdaUpdateWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getId, id)
                .set(RagIngestTaskEntity::getStatus, RagIngestStatus.SUCCESS.getCode())
                .set(RagIngestTaskEntity::getStage, "完成")
                .set(RagIngestTaskEntity::getProgress, 100)
                .set(RagIngestTaskEntity::getErrorMsg, null)
                .set(RagIngestTaskEntity::getFinishTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public void markFailed(Long id, String errorMsg) {
        mapper.update(null, new LambdaUpdateWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getId, id)
                .set(RagIngestTaskEntity::getStatus, RagIngestStatus.FAILED.getCode())
                .set(RagIngestTaskEntity::getStage, "失败")
                .set(RagIngestTaskEntity::getErrorMsg, errorMsg)
                .set(RagIngestTaskEntity::getFinishTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public void resetForRetry(Long id) {
        mapper.update(null, new LambdaUpdateWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getId, id)
                .set(RagIngestTaskEntity::getStatus, RagIngestStatus.PENDING.getCode())
                .set(RagIngestTaskEntity::getStage, "排队中")
                .set(RagIngestTaskEntity::getProgress, 0)
                .set(RagIngestTaskEntity::getErrorMsg, null)
                .set(RagIngestTaskEntity::getStartTime, null)
                .set(RagIngestTaskEntity::getFinishTime, null));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByDocId(Long docId) {
        if (docId == null) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getDocId, docId));
    }

    /** {@inheritDoc} */
    @Override
    public int deleteByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<RagIngestTaskEntity>()
                .eq(RagIngestTaskEntity::getKbId, kbId));
    }

    private RagIngestTask toDomain(RagIngestTaskEntity entity) {
        RagIngestTask task = new RagIngestTask();
        task.setId(entity.getId());
        task.setTenantId(entity.getTenantId());
        task.setKbId(entity.getKbId());
        task.setDocId(entity.getDocId());
        task.setStatus(entity.getStatus());
        task.setStage(entity.getStage());
        task.setProgress(entity.getProgress());
        task.setAttempts(entity.getAttempts());
        task.setErrorMsg(entity.getErrorMsg());
        // 域仍用 startTime/finishTime/createTime；库列已是 start_time/finish_time/create_time
        task.setStartTime(entity.getStartTime());
        task.setFinishTime(entity.getFinishTime());
        task.setCreatedBy(entity.getCreatedBy());
        task.setCreateTime(entity.getCreateTime());
        task.setUpdateTime(entity.getUpdateTime());
        return task;
    }
}
