package com.agenthub.admin.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页条件。所属域：admin。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageReq extends PageQuery {

    private String username;
    private Integer status;
    private Long deptId;
}
