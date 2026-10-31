package com.agenthub.obs.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.ObsTrace;
import com.agenthub.obs.domain.model.ObsTraceQuery;
import com.agenthub.obs.domain.model.TraceFinishSummary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * obs_trace 仓储。所属域：obs。实现不得把 Entity 交回应用层。
 */
public interface ObsTraceRepository {

    /**
     * @param trace      新 trace，状态由调用方给出
     * @param operatorId 创建人
     */
    void insert(ObsTrace trace, Long operatorId);

    /**
     * @param traceId 业务 traceId
     * @return 未删除的 trace
     */
    Optional<ObsTrace> findByTraceId(String traceId);

    /**
     * 只更新仍为 RUNNING 的行。已经是终态时不动。
     *
     * @param traceId      业务 traceId
     * @param status       终态
     * @param errorSummary 打码后的说明，可空
     * @param endTime      结束时间
     * @param operatorId   更新人，可空
     * @param summary      次数与 token 汇总，可空
     */
    void finishIfRunning(String traceId, ObsStatus status, String errorSummary, LocalDateTime endTime,
                         Long operatorId, TraceFinishSummary summary);

    /**
     * @param sessionId 试跑会话
     * @return 该会话下仍为 RUNNING 的 traceId
     */
    List<String> listRunningIds(long sessionId);

    /**
     * 按开始时间倒序。关键字只匹配 title。
     *
     * @param query 会话、可选用户和关键字
     * @return 一页 trace
     */
    PageResult<ObsTrace> page(ObsTraceQuery query);
}
