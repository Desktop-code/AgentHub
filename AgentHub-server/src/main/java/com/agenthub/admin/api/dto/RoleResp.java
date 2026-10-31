package com.agenthub.admin.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色出参。所属域：admin。列表接口不填菜单和自定义部门。
 */
@Data
public class RoleResp {

    private Long id;
    private String code;
    private String name;
    private Integer dataScope;
    private String dataScopeLabel;
    private Integer sort;
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private List<Long> menuIds = new ArrayList<>();
    private List<Long> customDeptIds = new ArrayList<>();
}
