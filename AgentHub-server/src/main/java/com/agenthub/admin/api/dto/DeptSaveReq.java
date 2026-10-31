package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门保存。所属域：admin。parentId 为空或 0 表示根。
 */
@Data
public class DeptSaveReq {

    private Long parentId;

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 64, message = "部门名称过长")
    private String name;

    private Integer sort;
    private Long leaderUserId;

    @Size(max = 20, message = "电话过长")
    private String phone;

    private Integer status;
}
