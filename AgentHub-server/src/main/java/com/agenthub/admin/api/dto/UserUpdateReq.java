package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改用户资料。所属域：admin。不能改登录名和密码。
 */
@Data
public class UserUpdateReq {

    private Long deptId;

    @Size(max = 64, message = "昵称过长")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Size(max = 20, message = "手机号过长")
    private String phone;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
