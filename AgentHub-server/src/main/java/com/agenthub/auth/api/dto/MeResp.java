package com.agenthub.auth.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前登录人的会话视图。所属域：auth。permissions 给按钮，menus 给侧栏。
 */
@Data
public class MeResp {

    private Long userId;
    private String username;
    private String nickname;
    private Long deptId;
    private String deptName;
    private boolean platformAdmin;
    private List<String> roles = new ArrayList<>();
    private List<String> permissions = new ArrayList<>();
    private List<MenuNodeResp> menus = new ArrayList<>();
}
