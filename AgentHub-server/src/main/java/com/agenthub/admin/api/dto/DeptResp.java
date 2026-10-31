package com.agenthub.admin.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点。所属域：admin。
 */
@Data
public class DeptResp {

    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private Integer sort;
    private Long leaderUserId;
    private String phone;
    private Integer status;
    private List<DeptResp> children = new ArrayList<>();
}
