package com.agenthub.admin.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门。所属域：admin。path 为逗号层级串，顶层从虚拟根 0 开始，如 0,1,3。
 */
@Data
public class Dept {

    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private Integer sort;
    private Long leaderUserId;
    private String phone;
    private Integer status;
    private List<Dept> children = new ArrayList<>();
}
