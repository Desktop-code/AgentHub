package com.agenthub.audit.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志。所属域：audit。只记录成败和账号，不记录密码。
 */
@Data
public class LoginLog {

    private Long id;
    private Long userId;
    private String username;
    private Integer loginType;
    private String ip;
    private String userAgent;
    private Integer success;
    private String message;
    private LocalDateTime createTime;
}
