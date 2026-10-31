package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_role_menu。所属域：admin。关联表物理删除。
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenuEntity {

    @TableId(value = "role_id", type = IdType.INPUT)
    private Long roleId;
    private Long menuId;
}
