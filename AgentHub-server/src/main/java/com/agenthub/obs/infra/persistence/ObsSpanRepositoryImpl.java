package com.agenthub.obs.infra.persistence;

import com.agenthub.obs.domain.model.ObsSpan;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.repository.ObsSpanRepository;
import com.agenthub.obs.infra.persistence.entity.ObsSpanEntity;
import com.agenthub.obs.infra.persistence.mapper.ObsSpanMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * obs_span 仓储实现。所属域：obs。Entity 不出本包。
 * 关闭时按 SpanClose 分列写 input/output 与专用元数据；null 不覆盖已有值。
 */
@Repository
public class ObsSpanRepositoryImpl implements ObsSpanRepository {

    private final ObsSpanMapper mapper;

    /**
     * @param mapper obs_span
     */
    public ObsSpanRepositoryImpl(ObsSpanMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public void insert(ObsSpan span) {
        ObsSpanEntity entity = new ObsSpanEntity();
        copyToEntity(span, entity);
        if (entity.getSpanType() == null) {
            entity.setSpanType(SpanType.SYSTEM.name());
        }
        if (entity.getStatus() == null) {
            entity.setStatus(ObsStatus.RUNNING.name());
        }
        if (entity.getStartTime() == null) {
            entity.setStartTime(LocalDateTime.now());
        }
        mapper.insert(entity);
        span.setId(entity.getId());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ObsSpan> find(String traceId, Long id) {
        ObsSpanEntity entity = mapper.selectOne(new LambdaQueryWrapper<ObsSpanEntity>()
                .eq(ObsSpanEntity::getTraceId, traceId)
                .eq(ObsSpanEntity::getId, id)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public void closeIfRunning(String traceId, Long id, SpanClose close, LocalDateTime endTime, Integer latencyMs) {
        ObsSpanEntity row = mapper.selectOne(new LambdaQueryWrapper<ObsSpanEntity>()
                .eq(ObsSpanEntity::getTraceId, traceId)
                .eq(ObsSpanEntity::getId, id)
                .last("LIMIT 1"));
        if (row == null || !ObsStatus.RUNNING.name().equals(row.getStatus()) || close == null || close.getStatus() == null) {
            return;
        }
        // 调用方传入墙钟 ms 时优先用，避免库内 datetime 无小数秒时 Duration 被截成整秒
        int resolvedLatency = latencyMs != null ? Math.max(0, latencyMs) : latency(row.getStartTime(), endTime);
        LambdaUpdateWrapper<ObsSpanEntity> update = new LambdaUpdateWrapper<ObsSpanEntity>()
                .eq(ObsSpanEntity::getId, row.getId())
                .eq(ObsSpanEntity::getStatus, ObsStatus.RUNNING.name())
                .set(ObsSpanEntity::getStatus, close.getStatus().name())
                .set(ObsSpanEntity::getEndTime, endTime)
                .set(ObsSpanEntity::getLatencyMs, resolvedLatency);
        setIfPresent(update, ObsSpanEntity::getInputText, close.getInputText());
        setIfPresent(update, ObsSpanEntity::getOutputText, close.getOutputText());
        setIfPresent(update, ObsSpanEntity::getErrorSummary, close.getErrorSummary());
        setIfPresent(update, ObsSpanEntity::getModelInstanceId, close.getModelInstanceId());
        setIfPresent(update, ObsSpanEntity::getModelName, close.getModelName());
        setIfPresent(update, ObsSpanEntity::getProvider, close.getProvider());
        setIfPresent(update, ObsSpanEntity::getTokenPrompt, close.getTokenPrompt());
        setIfPresent(update, ObsSpanEntity::getTokenCompletion, close.getTokenCompletion());
        setIfPresent(update, ObsSpanEntity::getTokenTotal, close.getTokenTotal());
        setIfPresent(update, ObsSpanEntity::getFinishReason, close.getFinishReason());
        setIfPresent(update, ObsSpanEntity::getBillingLedgerId, close.getBillingLedgerId());
        setIfPresent(update, ObsSpanEntity::getTemperature, close.getTemperature());
        setIfPresent(update, ObsSpanEntity::getMaxTokens, close.getMaxTokens());
        setIfPresent(update, ObsSpanEntity::getToolId, close.getToolId());
        setIfPresent(update, ObsSpanEntity::getToolName, close.getToolName());
        setIfPresent(update, ObsSpanEntity::getToolType, close.getToolType());
        setIfPresent(update, ObsSpanEntity::getToolCallId, close.getToolCallId());
        setIfPresent(update, ObsSpanEntity::getRetryCount, close.getRetryCount());
        setIfPresent(update, ObsSpanEntity::getToolSuccess, close.getToolSuccess());
        setIfPresent(update, ObsSpanEntity::getRouteDecision, close.getRouteDecision());
        setIfPresent(update, ObsSpanEntity::getChildAgentId, close.getChildAgentId());
        setIfPresent(update, ObsSpanEntity::getRetrieveQuery, close.getRetrieveQuery());
        setIfPresent(update, ObsSpanEntity::getRetrieveHitCount, close.getRetrieveHitCount());
        setIfPresent(update, ObsSpanEntity::getRetrieveTopK, close.getRetrieveTopK());
        setIfPresent(update, ObsSpanEntity::getRetrieveKbId, close.getRetrieveKbId());
        setIfPresent(update, ObsSpanEntity::getAttrsJson, close.getAttrsJson());
        mapper.update(null, update);
    }

    /** {@inheritDoc} */
    @Override
    public void closeRunning(String traceId, ObsStatus status, LocalDateTime endTime) {
        List<ObsSpanEntity> rows = mapper.selectList(new LambdaQueryWrapper<ObsSpanEntity>()
                .eq(ObsSpanEntity::getTraceId, traceId)
                .eq(ObsSpanEntity::getStatus, ObsStatus.RUNNING.name()));
        for (ObsSpanEntity row : rows) {
            mapper.update(null, new LambdaUpdateWrapper<ObsSpanEntity>()
                    .eq(ObsSpanEntity::getId, row.getId())
                    .eq(ObsSpanEntity::getStatus, ObsStatus.RUNNING.name())
                    .set(ObsSpanEntity::getStatus, status.name())
                    .set(ObsSpanEntity::getEndTime, endTime)
                    .set(ObsSpanEntity::getLatencyMs, latency(row.getStartTime(), endTime)));
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<ObsSpan> listByTraceId(String traceId) {
        return mapper.selectList(new LambdaQueryWrapper<ObsSpanEntity>()
                        .eq(ObsSpanEntity::getTraceId, traceId)
                        .orderByAsc(ObsSpanEntity::getSeq)
                        .orderByAsc(ObsSpanEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private void copyToEntity(ObsSpan span, ObsSpanEntity entity) {
        entity.setTraceId(span.getTraceId());
        entity.setParentId(span.getParentId());
        entity.setStepNo(span.getStepNo());
        entity.setSeq(span.getSeq());
        entity.setSpanType(span.getSpanType() == null ? null : span.getSpanType().name());
        entity.setName(span.getName());
        entity.setAgentId(span.getAgentId());
        entity.setStatus(span.getStatus() == null ? null : span.getStatus().name());
        entity.setStartTime(span.getStartTime());
        entity.setEndTime(span.getEndTime());
        entity.setLatencyMs(span.getLatencyMs());
        entity.setInputText(span.getInputText());
        entity.setOutputText(span.getOutputText());
        entity.setErrorSummary(span.getErrorSummary());
        entity.setModelInstanceId(span.getModelInstanceId());
        entity.setModelName(span.getModelName());
        entity.setProvider(span.getProvider());
        entity.setTokenPrompt(span.getTokenPrompt());
        entity.setTokenCompletion(span.getTokenCompletion());
        entity.setTokenTotal(span.getTokenTotal());
        entity.setFinishReason(span.getFinishReason());
        entity.setBillingLedgerId(span.getBillingLedgerId());
        entity.setTemperature(span.getTemperature());
        entity.setMaxTokens(span.getMaxTokens());
        entity.setToolId(span.getToolId());
        entity.setToolName(span.getToolName());
        entity.setToolType(span.getToolType());
        entity.setToolCallId(span.getToolCallId());
        entity.setRetryCount(span.getRetryCount());
        entity.setToolSuccess(span.getToolSuccess());
        entity.setRouteDecision(span.getRouteDecision());
        entity.setChildAgentId(span.getChildAgentId());
        entity.setRetrieveQuery(span.getRetrieveQuery());
        entity.setRetrieveHitCount(span.getRetrieveHitCount());
        entity.setRetrieveTopK(span.getRetrieveTopK());
        entity.setRetrieveKbId(span.getRetrieveKbId());
        entity.setAttrsJson(span.getAttrsJson());
        entity.setContent(span.getContent());
    }

    private ObsSpan toDomain(ObsSpanEntity entity) {
        ObsSpan span = new ObsSpan();
        span.setId(entity.getId());
        span.setTraceId(entity.getTraceId());
        span.setParentId(entity.getParentId());
        span.setStepNo(entity.getStepNo());
        span.setSeq(entity.getSeq());
        span.setSpanType(spanType(entity.getSpanType()));
        span.setName(entity.getName());
        span.setAgentId(entity.getAgentId());
        span.setStatus(status(entity.getStatus()));
        span.setStartTime(entity.getStartTime());
        span.setEndTime(entity.getEndTime());
        span.setLatencyMs(entity.getLatencyMs());
        span.setInputText(entity.getInputText());
        span.setOutputText(entity.getOutputText());
        span.setErrorSummary(entity.getErrorSummary());
        span.setModelInstanceId(entity.getModelInstanceId());
        span.setModelName(entity.getModelName());
        span.setProvider(entity.getProvider());
        span.setTokenPrompt(entity.getTokenPrompt());
        span.setTokenCompletion(entity.getTokenCompletion());
        span.setTokenTotal(entity.getTokenTotal());
        span.setFinishReason(entity.getFinishReason());
        span.setBillingLedgerId(entity.getBillingLedgerId());
        span.setTemperature(entity.getTemperature());
        span.setMaxTokens(entity.getMaxTokens());
        span.setToolId(entity.getToolId());
        span.setToolName(entity.getToolName());
        span.setToolType(entity.getToolType());
        span.setToolCallId(entity.getToolCallId());
        span.setRetryCount(entity.getRetryCount());
        span.setToolSuccess(entity.getToolSuccess());
        span.setRouteDecision(entity.getRouteDecision());
        span.setChildAgentId(entity.getChildAgentId());
        span.setRetrieveQuery(entity.getRetrieveQuery());
        span.setRetrieveHitCount(entity.getRetrieveHitCount());
        span.setRetrieveTopK(entity.getRetrieveTopK());
        span.setRetrieveKbId(entity.getRetrieveKbId());
        span.setAttrsJson(entity.getAttrsJson());
        span.setContent(entity.getContent());
        span.setCreateTime(entity.getCreateTime());
        span.setUpdateTime(entity.getUpdateTime());
        return span;
    }

    private <T> void setIfPresent(LambdaUpdateWrapper<ObsSpanEntity> update,
                                  com.baomidou.mybatisplus.core.toolkit.support.SFunction<ObsSpanEntity, ?> column,
                                  T value) {
        if (value != null) {
            update.set(column, value);
        }
    }

    private SpanType spanType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SpanType.valueOf(raw);
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
