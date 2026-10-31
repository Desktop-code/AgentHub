package com.agenthub.auth.api.dto;

import lombok.Data;

/**
 * 登录成功后交给前端保存的令牌。所属域：auth。token 是裸值，请求时再加 Bearer。
 */
@Data
public class LoginResp {

    private String token;
    private Long userId;
    private String username;
    private String nickname;
}
