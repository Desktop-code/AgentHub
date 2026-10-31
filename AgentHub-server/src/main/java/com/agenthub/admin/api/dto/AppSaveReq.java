package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 应用资料。所属域：admin。密钥不在这里提交。
 */
@Data
public class AppSaveReq {

    @NotBlank(message = "应用名称不能为空")
    @Size(max = 64, message = "应用名称过长")
    private String name;

    private Long deptId;
    private Integer status;

    @Size(max = 255, message = "备注过长")
    private String remark;
}
