package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * sys_role。所属域：admin。data_scope 的取值见 DataScope。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRoleEntity extends AuditableEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Integer dataScope;
    private Integer sort;
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;
}
