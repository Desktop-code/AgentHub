package com.agenthub.auth.application;

import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.auth.api.dto.AvatarUploadResp;
import com.agenthub.auth.api.dto.ProfileResp;
import com.agenthub.auth.api.dto.ProfileUpdateReq;
import com.agenthub.auth.infra.storage.AvatarFileStorage;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前登录人个人资料。所属域：auth。
 * <p>
 * 只允许改自己的 nickname / avatar_url；管理员改别人头像不在本刀。
 * 复用 sys_user 既有列，不另建用户表。
 */
@Service
public class ProfileAppService {

    private final UserRepository users;
    private final OperatorContext operatorContext;
    private final AvatarFileStorage avatarStorage;

    /**
     * @param users            用户仓储
     * @param operatorContext  当前登录人
     * @param avatarStorage    头像落盘
     */
    public ProfileAppService(UserRepository users, OperatorContext operatorContext, AvatarFileStorage avatarStorage) {
        this.users = users;
        this.operatorContext = operatorContext;
        this.avatarStorage = avatarStorage;
    }

    /**
     * @return 本人资料（含邮箱/手机只读字段，便于前端有则展示）
     * @throws BizException 未登录或用户已删
     */
    public ProfileResp get() {
        return toResp(requireSelf());
    }

    /**
     * 只改显示名。
     *
     * @param req 新显示名
     * @return 更新后的资料
     */
    @Transactional
    public ProfileResp updateNickname(ProfileUpdateReq req) {
        User self = requireSelf();
        String nickname = req.getNickname() == null ? "" : req.getNickname().trim();
        if (nickname.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "显示名不能为空");
        }
        if (nickname.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "显示名最长 64");
        }
        users.updateNicknameAndAvatar(self.getId(), nickname, self.getAvatarUrl(), operatorContext.requireUserId());
        self.setNickname(nickname);
        return toResp(self);
    }

    /**
     * 上传并更换头像。校验类型与体积后落盘，旧文件尽力删除。
     *
     * @param file 图片文件
     * @return 新 avatarUrl
     */
    @Transactional
    public AvatarUploadResp uploadAvatar(MultipartFile file) {
        User self = requireSelf();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的图片");
        }
        if (file.getSize() > AvatarFileStorage.MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "头像不能超过 2MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!contentType.startsWith("image/")
                || !(contentType.contains("jpeg") || contentType.contains("jpg")
                || contentType.contains("png") || contentType.contains("webp"))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 jpg / png / webp 图片");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
        String newUrl;
        try {
            newUrl = avatarStorage.save(self.getId(), file.getOriginalFilename(), bytes);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new BizException(ErrorCode.INTERNAL, ex.getMessage());
        }
        String oldUrl = self.getAvatarUrl();
        users.updateNicknameAndAvatar(self.getId(), self.getNickname(), newUrl, operatorContext.requireUserId());
        // 先写库再删旧文件：删失败不影响主路径
        avatarStorage.deleteByUrl(oldUrl);
        AvatarUploadResp resp = new AvatarUploadResp();
        resp.setAvatarUrl(newUrl);
        return resp;
    }

    /**
     * 清除头像：库字段置空并尽力删文件；侧栏回退为首字兜底。
     *
     * @return 清除后的资料
     */
    @Transactional
    public ProfileResp clearAvatar() {
        User self = requireSelf();
        String oldUrl = self.getAvatarUrl();
        users.updateNicknameAndAvatar(self.getId(), self.getNickname(), null, operatorContext.requireUserId());
        avatarStorage.deleteByUrl(oldUrl);
        self.setAvatarUrl(null);
        return toResp(self);
    }

    private User requireSelf() {
        Long id = operatorContext.requireUserId();
        return users.findById(id).orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }

    private static ProfileResp toResp(User user) {
        ProfileResp resp = new ProfileResp();
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setAvatarUrl(user.getAvatarUrl());
        resp.setEmail(user.getEmail());
        resp.setPhone(user.getPhone());
        return resp;
    }
}
