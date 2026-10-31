package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * sys_menu。所属域：admin。权限码列名是 permission。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenuEntity extends AuditableEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private Integer type;
    private String name;
    private String path;
    private String component;
    private String redirect;
    private String permission;
    private String icon;
    private Integer sort;
    private Integer visible;
    private Integer status;
    private Integer openType;

    @TableLogic
    private Integer deleted;
}
