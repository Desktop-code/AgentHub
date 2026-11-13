package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * Agent 自定义形象上传结果。所属域：agent。
 * <p>只回本站相对 URL；前端拿它填进弹窗，点确定后才随 Agent 保存落库。
 */
@Data
public class AgentAvatarUploadResp {

    /** 相对 URL，如 /uploads/agent-avatars/12/xxx.png */
    private String url;
}
