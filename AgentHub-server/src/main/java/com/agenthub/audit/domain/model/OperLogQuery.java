package com.agenthub.audit.domain.model;

import lombok.Data;

/**
 * 操作日志分页条件。所属域：audit。
 */
@Data
public class OperLogQuery {

    private long current = 1;
    private long size = 20;
    private String module;
    private String username;
}
