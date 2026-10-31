package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sys_user。所属域：admin。password_hash 对应列名，不是 password。
 * 时间列：last_login_time / create_time / update_time。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUserEntity extends AuditableEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deptId;
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String phone;
    private String avatarUrl;
    private Integer gender;
    private Integer status;
    private Integer isAdmin;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;

    @TableLogic
    private Integer deleted;

    /** 列表查询投影，不是表字段 */
    @TableField(exist = false)
    private String deptName;

    /** 列表查询投影，供数据范围前缀判断 */
    @TableField(exist = false)
    private String deptPath;
}
