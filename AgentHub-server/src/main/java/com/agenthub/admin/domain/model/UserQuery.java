package com.agenthub.admin.domain.model;

import lombok.Data;

/**
 * 用户分页条件。所属域：admin。username 已在应用层去掉 LIKE 通配符。
 */
@Data
public class UserQuery {

    private long current = 1;
    private long size = 20;
    private String username;
    private Integer status;
    private Long deptId;
}
