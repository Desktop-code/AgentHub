package com.agenthub.audit.domain.repository;

import com.agenthub.audit.domain.model.LoginLog;
import com.agenthub.audit.domain.model.LoginLogQuery;
import com.agenthub.common.api.PageResult;

/**
 * 登录日志仓储。所属域：audit。
 */
public interface LoginLogRepository {

    /**
     * 追加一条登录记录。失败也要落库，因此实现使用独立事务。
     *
     * @param log 已填好的日志，不含主键
     */
    void insert(LoginLog log);

    /**
     * @param query 分页条件
     * @return 一页日志，按时间倒序
     */
    PageResult<LoginLog> page(LoginLogQuery query);
}
