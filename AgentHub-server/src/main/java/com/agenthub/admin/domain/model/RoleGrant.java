package com.agenthub.admin.domain.model;

import com.agenthub.admin.domain.enums.DataScope;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 参与数据范围合并的一条角色。所属域：admin。停用角色由解析器忽略。
 */
@Data
@AllArgsConstructor
public class RoleGrant {

    private DataScope dataScope;
    private Integer status;
    private List<Long> customDeptIds;
}
