package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_user_role。所属域：admin。关联表物理删除。
 */
@Data
@TableName("sys_user_role")
public class SysUserRoleEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private Long roleId;
}
