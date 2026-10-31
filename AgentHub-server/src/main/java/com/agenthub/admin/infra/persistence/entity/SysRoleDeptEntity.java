package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_role_dept。所属域：admin。无逻辑删除，调整范围时物理替换。
 */
@Data
@TableName("sys_role_dept")
public class SysRoleDeptEntity {

    @TableId(value = "role_id", type = IdType.INPUT)
    private Long roleId;
    private Long deptId;
}
