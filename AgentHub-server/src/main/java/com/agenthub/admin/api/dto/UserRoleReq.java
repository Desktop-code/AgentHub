package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 覆盖式分配角色。所属域：admin。空列表表示清空角色。
 */
@Data
public class UserRoleReq {

    @NotNull(message = "角色列表不能为空")
    private List<Long> roleIds;
}
