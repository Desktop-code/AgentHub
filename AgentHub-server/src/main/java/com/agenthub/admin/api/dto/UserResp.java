package com.agenthub.admin.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户出参。所属域：admin。不含密码哈希。
 */
@Data
public class UserResp {

    private Long id;
    private String username;
    private String nickname;
    private Long deptId;
    private String deptName;
    private String email;
    private String phone;
    private Integer status;
    private boolean platformAdmin;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private List<Long> roleIds = new ArrayList<>();
    private List<String> roleNames = new ArrayList<>();
}
