package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 角色菜单。所属域：admin。服务端会补上祖先节点。
 */
@Data
public class RoleMenuReq {

    @NotNull(message = "菜单不能为空")
    private List<Long> menuIds;
}
