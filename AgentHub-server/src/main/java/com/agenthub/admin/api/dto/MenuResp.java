package com.agenthub.admin.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点。所属域：admin。管理页保留按钮。
 */
@Data
public class MenuResp {

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
    private List<MenuResp> children = new ArrayList<>();
}
