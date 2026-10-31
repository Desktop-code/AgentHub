package com.agenthub.admin.domain.model;

import lombok.Data;

/**
 * 角色分页条件。所属域：admin。
 */
@Data
public class RoleQuery {

    private long current = 1;
    private long size = 20;
    private String code;
    private String name;
    private Integer status;
}
