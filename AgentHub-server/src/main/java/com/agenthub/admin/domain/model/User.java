package com.agenthub.admin.domain.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台用户。所属域：admin。不是表实体，密码哈希只在登录校验时使用，不进入响应 DTO。
 */
@Data
public class User {

    private Long id;
    private Long deptId;
    private String deptName;
    /** 所属部门 path，供数据范围判断，不返回给前端 */
    private String deptPath;
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String phone;
    /** 头像相对 URL（/uploads/avatars/...）；未上传为空 */
    private String avatarUrl;
    private Integer status;
    /** sys_user.is_admin = 1 */
    private boolean platformAdmin;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private List<Long> roleIds = new ArrayList<>();
    private List<String> roleNames = new ArrayList<>();
}
