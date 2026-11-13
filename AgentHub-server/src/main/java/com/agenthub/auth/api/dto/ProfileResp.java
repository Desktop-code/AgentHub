package com.agenthub.auth.api.dto;

import lombok.Data;

/**
 * 当前登录人的个人资料视图。所属域：auth。
 * <p>只含本人可改的展示字段；不含密码哈希、权限码或菜单树。
 */
@Data
public class ProfileResp {

    private Long userId;
    private String username;
    /** 显示名；侧栏与工作台气泡优先用它 */
    private String nickname;
    /** 头像访问路径（如 /uploads/avatars/...）；未上传为空 */
    private String avatarUrl;
    private String email;
    private String phone;
}
