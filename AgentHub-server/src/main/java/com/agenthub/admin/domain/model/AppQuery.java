package com.agenthub.admin.domain.model;

import lombok.Data;

/**
 * 应用分页条件。所属域：admin。
 */
@Data
public class AppQuery {

    private long current = 1;
    private long size = 20;
    private String name;
    private Integer status;
}
