package com.agenthub.admin.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色分页条件。所属域：admin。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RolePageReq extends PageQuery {

    private String code;
    private String name;
    private Integer status;
}
