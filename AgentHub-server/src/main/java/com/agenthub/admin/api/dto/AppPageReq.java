package com.agenthub.admin.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用分页条件。所属域：admin。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AppPageReq extends PageQuery {

    private String name;
    private Integer status;
}
