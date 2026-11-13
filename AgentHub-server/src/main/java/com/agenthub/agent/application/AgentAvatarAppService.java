package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentAvatarUploadResp;
import com.agenthub.agent.infra.storage.AgentAvatarStorage;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent 自定义形象上传。所属域：agent。
 * <p>
 * 只负责落盘并回 URL；是否采用由配置页点「确定」后随 Agent 保存写入 avatar_config，
 * 避免「上传了一半就改了形象」。校验类型与体积，禁止可执行文件。
 */
@Service
public class AgentAvatarAppService {

    private final AgentAvatarStorage storage;
    private final OperatorContext operatorContext;

    /**
     * @param storage         落盘
     * @param operatorContext 上传人
     */
    public AgentAvatarAppService(AgentAvatarStorage storage, OperatorContext operatorContext) {
        this.storage = storage;
        this.operatorContext = operatorContext;
    }

    /**
     * 上传图片并返回本站相对 URL。
     *
     * @param file 图片（jpg/png/webp，≤2MB）
     * @return URL
     * @throws BizException 未登录、空文件、类型或体积不合法
     */
    public AgentAvatarUploadResp upload(MultipartFile file) {
        Long operatorId = operatorContext.requireUserId();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的图片");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!contentType.startsWith("image/")
                || !(contentType.contains("jpeg") || contentType.contains("jpg")
                || contentType.contains("png") || contentType.contains("webp"))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 jpg / png / webp 图片");
        }
        if (file.getSize() > AgentAvatarStorage.MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "形象图片不能超过 2MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
        String url;
        try {
            url = storage.save(operatorId, file.getOriginalFilename(), bytes);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new BizException(ErrorCode.INTERNAL, ex.getMessage());
        }
        AgentAvatarUploadResp resp = new AgentAvatarUploadResp();
        resp.setUrl(url);
        return resp;
    }
}
