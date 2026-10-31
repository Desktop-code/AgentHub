package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单保存。所属域：admin。按钮必须带权限码。
 */
@Data
public class MenuSaveReq {

    private Long parentId;

    @NotNull(message = "菜单类型不能为空")
    private Integer type;

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称过长")
    private String name;

    @Size(max = 200, message = "路由过长")
    private String path;

    @Size(max = 255, message = "组件过长")
    private String component;

    @Size(max = 255, message = "重定向过长")
    private String redirect;

    @Size(max = 128, message = "权限码过长")
    private String permission;

    @Size(max = 64, message = "图标过长")
    private String icon;

    private Integer sort;
    private Integer visible;
    private Integer status;
    private Integer openType;
}
