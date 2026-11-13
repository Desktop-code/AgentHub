package com.agenthub.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 本人改显示名。所属域：auth。
 * <p>不能改登录名；邮箱/手机本刀只读展示，不在此请求里改。
 */
@Data
public class ProfileUpdateReq {

    @NotBlank(message = "显示名不能为空")
    @Size(max = 64, message = "显示名最长 64")
    private String nickname;
}
