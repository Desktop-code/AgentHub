package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * sys_app。所属域：admin。对外标识是 app_key，密钥只存 app_secret_hash。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_app")
public class SysAppEntity extends AuditableEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appKey;
    private String appSecretHash;
    private String name;
    private Long deptId;
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;

    /** 列表投影，不是表字段 */
    @TableField(exist = false)
    private String deptName;
}
