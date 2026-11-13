package com.agenthub.auth.api;

import com.agenthub.auth.api.dto.AvatarUploadResp;
import com.agenthub.auth.api.dto.ProfileResp;
import com.agenthub.auth.api.dto.ProfileUpdateReq;
import com.agenthub.auth.application.ProfileAppService;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人设置接口。所属域：auth。
 * <p>必须登录；只操作当前登录人自己的资料，不走 system:user:edit。
 */
@RestController
@RequestMapping("/api/auth/profile")
public class ProfileController {

    private final ProfileAppService profiles;

    /**
     * @param profiles 个人资料用例
     */
    public ProfileController(ProfileAppService profiles) {
        this.profiles = profiles;
    }

    /**
     * @return 本人资料
     */
    @GetMapping
    public Result<ProfileResp> get() {
        return Result.ok(profiles.get());
    }

    /**
     * 改显示名。
     *
     * @param req 新昵称
     * @return 更新后的资料
     */
    @PutMapping
    public Result<ProfileResp> update(@Valid @RequestBody ProfileUpdateReq req) {
        return Result.ok(profiles.updateNickname(req));
    }

    /**
     * 上传/更换头像。
     *
     * @param file 图片（jpg/png/webp，≤2MB）
     * @return 新 avatarUrl
     */
    @PostMapping("/avatar")
    public Result<AvatarUploadResp> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.ok(profiles.uploadAvatar(file));
    }

    /**
     * 清除头像。
     *
     * @return 清除后的资料
     */
    @DeleteMapping("/avatar")
    public Result<ProfileResp> clearAvatar() {
        return Result.ok(profiles.clearAvatar());
    }
}
