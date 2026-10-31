package com.agenthub.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录入参。所属域：auth。密码只在这次请求里出现，不进日志。
 */
@Data
public class LoginReq {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
