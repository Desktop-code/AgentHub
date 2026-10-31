package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据范围。所属域：admin。只有自定义部门才会使用 deptIds。
 */
@Data
public class RoleDataScopeReq {

    @NotNull(message = "数据范围不能为空")
    private Integer dataScope;

    private List<Long> deptIds = new ArrayList<>();
}
