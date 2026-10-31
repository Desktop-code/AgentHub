package com.agenthub.audit.domain.model;

import lombok.Data;

/**
 * 登录日志分页条件。所属域：audit。
 */
@Data
public class LoginLogQuery {

    private long current = 1;
    private long size = 20;
    private String username;
    private Integer success;
}
