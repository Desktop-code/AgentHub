package com.agenthub.auth.api.dto;

import lombok.Data;

/**
 * 头像上传结果。所属域：auth。
 * <p>只回公开可访问的相对路径，不回本机绝对路径或密钥。
 */
@Data
public class AvatarUploadResp {

    /** 写入 sys_user.avatar_url 的相对 URL，如 /uploads/avatars/1/xxx.png */
    private String avatarUrl;
}
