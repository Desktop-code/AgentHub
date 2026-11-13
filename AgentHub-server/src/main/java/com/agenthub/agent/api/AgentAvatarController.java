package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.agent.api.dto.AgentAvatarUploadResp;
import com.agenthub.agent.application.AgentAvatarAppService;
import com.agenthub.common.api.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent 形象上传接口。所属域：agent。
 * <p>
 * 需要 {@code agent:edit}：能改 Agent 配置的人才可上传。
 * 用 {@code /avatar-upload} 而不是 {@code /{id}} 形态，避免与详情主键路径混淆。
 */
@RestController
@RequestMapping("/api/agent/agents")
public class AgentAvatarController {

    private final AgentAvatarAppService avatars;

    /**
     * @param avatars 上传用例
     */
    public AgentAvatarController(AgentAvatarAppService avatars) {
        this.avatars = avatars;
    }

    /**
     * 上传自定义形象，返回本站相对 URL（尚未落库）。
     *
     * @param file 图片（jpg/png/webp，≤2MB）
     * @return URL
     */
    @SaCheckPermission(AgentPermissions.EDIT)
    @PostMapping("/avatar-upload")
    public Result<AgentAvatarUploadResp> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(avatars.upload(file));
    }
}
