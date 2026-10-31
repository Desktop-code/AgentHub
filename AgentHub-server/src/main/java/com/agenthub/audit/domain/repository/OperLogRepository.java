package com.agenthub.audit.domain.repository;

import com.agenthub.audit.domain.model.OperLog;
import com.agenthub.audit.domain.model.OperLogQuery;
import com.agenthub.common.api.PageResult;

/**
 * 操作日志仓储。所属域：audit。
 */
public interface OperLogRepository {

    /**
     * @param log 操作摘要
     */
    void insert(OperLog log);

    /**
     * @param query 分页条件
     * @return 一页日志，按时间倒序
     */
    PageResult<OperLog> page(OperLogQuery query);
}
