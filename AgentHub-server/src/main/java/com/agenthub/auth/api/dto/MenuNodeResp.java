package com.agenthub.auth.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 侧栏节点。所属域：auth。不含按钮。
 * type 与 sys_menu 一致：1 可折叠目录、2 菜单、4 无折叠分组标题。
 */
@Data
public class MenuNodeResp {

    private Long id;
    private Long parentId;
    /** 菜单类型：1 目录 / 2 菜单 / 4 目录（无折叠） */
    private Integer type;
    private String name;
    private String path;
    private String component;
    private String icon;
    private Integer sort;
    private Integer visible;
    private List<MenuNodeResp> children = new ArrayList<>();
}
