package com.agenthub.obs.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.obs.domain.model.ObsChannel;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.ObsTrace;
import com.agenthub.obs.domain.model.ObsTraceQuery;
import com.agenthub.obs.domain.model.TraceFinishSummary;
import com.agenthub.obs.domain.repository.ObsTraceRepository;
import com.agenthub.obs.infra.persistence.entity.ObsTraceEntity;
import com.agenthub.obs.infra.persistence.mapper.ObsTraceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * obs_trace 仓储实现。所属域：obs。Entity 不出本包。
 * 编排预留列不在 insert 里赋值，保持数据库默认 NULL。
 */
@Repository
public class ObsTraceRepositoryImpl implements ObsTraceRepository {

    private final ObsTraceMapper mapper;

    /**
     * @param mapper obs_trace
     */
    public ObsTraceRepositoryImpl(ObsTraceMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public void insert(ObsTrace trace, Long operatorId) {
        ObsTraceEntity entity = new ObsTraceEntity();
        entity.setTraceId(trace.getTraceId());
        entity.setSessionId(trace.getSessionId());
        entity.setAgentId(trace.getAgentId());
        entity.setUserId(trace.getUserId());
        entity.setAppId(trace.getAppId());
        entity.setChannel(trace.getChannel() == null ? ObsChannel.CONSOLE.name() : trace.getChannel().name());
        entity.setTitle(trace.getTitle());
        entity.setStatus(trace.getStatus() == null ? ObsStatus.RUNNING.name() : trace.getStatus().name());
        entity.setStartTime(trace.getStartTime() == null ? LocalDateTime.now() : trace.getStartTime());
        entity.setEndTime(trace.getEndTime());
        entity.setLatencyMs(trace.getLatencyMs());
        entity.setErrorSummary(trace.getErrorSummary());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        trace.setId(entity.getId());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ObsTrace> findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        ObsTraceEntity entity = mapper.selectOne(new LambdaQueryWrapper<ObsTraceEntity>()
                .eq(ObsTraceEntity::getTraceId, traceId)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public void finishIfRunning(String traceId, ObsStatus status, String errorSummary, LocalDateTime endTime,
                                Long operatorId, TraceFinishSummary summary) {
        ObsTraceEntity row = mapper.selectOne(new LambdaQueryWrapper<ObsTraceEntity>()
                .eq(ObsTraceEntity::getTraceId, traceId)
                .eq(ObsTraceEntity::getStatus, ObsStatus.RUNNING.name())
                .last("LIMIT 1"));
        if (row == null) {
            return;
        }
        LambdaUpdateWrapper<ObsTraceEntity> update = new LambdaUpdateWrapper<ObsTraceEntity>()
                .eq(ObsTraceEntity::getId, row.getId())
                .eq(ObsTraceEntity::getStatus, ObsStatus.RUNNING.name())
                .set(ObsTraceEntity::getStatus, status.name())
                .set(ObsTraceEntity::getEndTime, endTime)
                .set(ObsTraceEntity::getLatencyMs, latency(row.getStartTime(), endTime))
                .set(ObsTraceEntity::getUpdatedBy, operatorId);
        if (errorSummary != null) {
            update.set(ObsTraceEntity::getErrorSummary, errorSummary);
        }
        if (summary != null) {
            if (summary.getRoundCount() != null) {
                update.set(ObsTraceEntity::getRoundCount, summary.getRoundCount());
            }
            if (summary.getLlmCallCount() != null) {
                update.set(ObsTraceEntity::getLlmCallCount, summary.getLlmCallCount());
            }
            if (summary.getToolCallCount() != null) {
                update.set(ObsTraceEntity::getToolCallCount, summary.getToolCallCount());
            }
            if (summary.getRetrieveCallCount() != null) {
                update.set(ObsTraceEntity::getRetrieveCallCount, summary.getRetrieveCallCount());
            }
            if (summary.getTokenPrompt() != null) {
                update.set(ObsTraceEntity::getTokenPrompt, summary.getTokenPrompt());
            }
            if (summary.getTokenCompletion() != null) {
                update.set(ObsTraceEntity::getTokenCompletion, summary.getTokenCompletion());
            }
            if (summary.getTokenTotal() != null) {
                update.set(ObsTraceEntity::getTokenTotal, summary.getTokenTotal());
            }
            if (summary.getPrimaryModelInstanceId() != null) {
                update.set(ObsTraceEntity::getPrimaryModelInstanceId, summary.getPrimaryModelInstanceId());
            }
            if (summary.getPrimaryModelName() != null) {
                update.set(ObsTraceEntity::getPrimaryModelName, summary.getPrimaryModelName());
            }
            if (summary.getFinishReason() != null) {
                update.set(ObsTraceEntity::getFinishReason, summary.getFinishReason());
            }
            if (summary.getAttrsJson() != null) {
                update.set(ObsTraceEntity::getAttrsJson, summary.getAttrsJson());
            }
        }
        mapper.update(null, update);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> listRunningIds(long sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<ObsTraceEntity>()
                        .eq(ObsTraceEntity::getSessionId, sessionId)
                        .eq(ObsTraceEntity::getStatus, ObsStatus.RUNNING.name()))
                .stream()
                .map(ObsTraceEntity::getTraceId)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<ObsTrace> page(ObsTraceQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        LambdaQueryWrapper<ObsTraceEntity> wrapper = new LambdaQueryWrapper<ObsTraceEntity>()
                .eq(ObsTraceEntity::getSessionId, query.getSessionId())
                .orderByDesc(ObsTraceEntity::getStartTime)
                .orderByDesc(ObsTraceEntity::getId);
        if (query.getUserId() != null) {
            wrapper.eq(ObsTraceEntity::getUserId, query.getUserId());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(ObsTraceEntity::getTitle, query.getKeyword().trim());
        }
        IPage<ObsTraceEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    private ObsTrace toDomain(ObsTraceEntity entity) {
        ObsTrace trace = new ObsTrace();
        trace.setId(entity.getId());
        trace.setTraceId(entity.getTraceId());
        trace.setSessionId(entity.getSessionId());
        trace.setAgentId(entity.getAgentId());
        trace.setUserId(entity.getUserId());
        trace.setAppId(entity.getAppId());
        trace.setChannel(channel(entity.getChannel()));
        trace.setTitle(entity.getTitle());
        trace.setWorkflowRunId(entity.getWorkflowRunId());
        trace.setTaskNodeId(entity.getTaskNodeId());
        trace.setRootTraceId(entity.getRootTraceId());
        trace.setStatus(status(entity.getStatus()));
        trace.setStartTime(entity.getStartTime());
        trace.setEndTime(entity.getEndTime());
        trace.setLatencyMs(entity.getLatencyMs());
        trace.setErrorSummary(entity.getErrorSummary());
        trace.setRoundCount(entity.getRoundCount());
        trace.setLlmCallCount(entity.getLlmCallCount());
        trace.setToolCallCount(entity.getToolCallCount());
        trace.setRetrieveCallCount(entity.getRetrieveCallCount());
        trace.setTokenPrompt(entity.getTokenPrompt());
        trace.setTokenCompletion(entity.getTokenCompletion());
        trace.setTokenTotal(entity.getTokenTotal());
        trace.setPrimaryModelInstanceId(entity.getPrimaryModelInstanceId());
        trace.setPrimaryModelName(entity.getPrimaryModelName());
        trace.setFinishReason(entity.getFinishReason());
        trace.setAttrsJson(entity.getAttrsJson());
        trace.setCreateTime(entity.getCreateTime());
        trace.setUpdateTime(entity.getUpdateTime());
        return trace;
    }

    private ObsChannel channel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ObsChannel.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ObsStatus status(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ObsStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int latency(LocalDateTime started, LocalDateTime ended) {
        if (started == null || ended == null) {
            return 0;
        }
        long ms = Duration.between(started, ended).toMillis();
        if (ms < 0) {
            return 0;
        }
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }
}
