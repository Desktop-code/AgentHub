package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 新建用户。所属域：admin。初始密码由服务端配置生成，请求里不传密码。
 */
@Data
public class UserCreateReq {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,64}$", message = "用户名需为 3-64 位字母、数字或下划线")
    private String username;

    @Size(max = 64, message = "昵称过长")
    private String nickname;

    private Long deptId;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Size(max = 20, message = "手机号过长")
    private String phone;

    @NotNull(message = "状态不能为空")
    private Integer status;

    private List<Long> roleIds = new ArrayList<>();
}
