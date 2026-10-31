package com.agenthub.admin.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单或按钮。所属域：admin。
 * type：1 可折叠目录、2 页面、3 按钮（只贡献权限码，不进导航树）、4 无折叠分组标题。
 */
@Data
public class Menu {

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
    private List<Menu> children = new ArrayList<>();
}
