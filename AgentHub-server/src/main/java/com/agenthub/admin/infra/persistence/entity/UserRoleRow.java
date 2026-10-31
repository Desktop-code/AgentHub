package com.agenthub.admin.infra.persistence.entity;

import lombok.Data;

/**
 * 用户与角色名称的投影。所属域：admin。给列表一次查出角色，避免逐行查询。
 */
@Data
public class UserRoleRow {

    private Long userId;
    private Long roleId;
    private String roleName;
}
