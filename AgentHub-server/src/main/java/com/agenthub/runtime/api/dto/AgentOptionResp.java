package com.agenthub.runtime.api.dto;

import lombok.Data;

/**
 * 试跑页可选的已启用 Agent。所属域：runtime。不含系统提示词。
 * 带上视觉能力标记，前端据此决定「上传图片」按钮是否可用，
 * 避免用户传完图才在发送时被告知没有 VISION 模型。
 */
@Data
public class AgentOptionResp {

    private Long id;
    private String name;
    private String code;
    private String runMode;

    /** 是否已绑定可用的 VISION 模型。false 时前端置灰上传按钮 */
    private Boolean visionEnabled;
}
