package com.agenthub.obs.domain.repository;

import com.agenthub.obs.domain.model.ObsSpan;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * obs_span 仓储。所属域：obs。实现不得把 Entity 交回应用层。
 * 这张表没有逻辑删除列。树键为 id + parent_id。
 */
public interface ObsSpanRepository {

    /**
     * 插入后回填 {@link ObsSpan#getId()}。
     *
     * @param span 新步骤
     */
    void insert(ObsSpan span);

    /**
     * @param traceId 业务 traceId
     * @param id      行主键
     * @return 步骤
     */
    Optional<ObsSpan> find(String traceId, Long id);

    /**
     * 只更新仍为 RUNNING 的步骤。null 字段不覆盖已有正文/专用列。
     *
     * @param traceId   业务 traceId
     * @param id        行主键
     * @param close     终态与分列正文、元数据
     * @param endTime   结束时间
     * @param latencyMs 墙钟耗时；非空时优先写入，避免 datetime 秒截断导致 latency 失真
     */
    void closeIfRunning(String traceId, Long id, SpanClose close, LocalDateTime endTime, Integer latencyMs);

    /**
     * 把这条 trace 上仍为 RUNNING 的步骤收成同一终态，正文保持原样。
     *
     * @param traceId 业务 traceId
     * @param status  终态
     * @param endTime 结束时间
     */
    void closeRunning(String traceId, ObsStatus status, LocalDateTime endTime);

    /**
     * @param traceId 业务 traceId
     * @return 按 seq 升序的全部步骤
     */
    List<ObsSpan> listByTraceId(String traceId);
}
