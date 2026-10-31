package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建或修改角色。所属域：admin。修改时忽略 code，编码创建后不变。
 */
@Data
public class RoleSaveReq {

    @NotBlank(message = "角色编码不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{1,63}$", message = "角色编码需以字母开头")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称过长")
    private String name;

    @NotNull(message = "数据范围不能为空")
    private Integer dataScope;

    private Integer sort;
    private Integer status;

    @Size(max = 255, message = "备注过长")
    private String remark;
}
