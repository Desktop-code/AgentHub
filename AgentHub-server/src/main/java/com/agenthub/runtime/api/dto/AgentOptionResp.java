package com.agenthub.runtime.api.dto;

import lombok.Data;

/**
 * 试跑页可选的已启用 Agent。所属域：runtime。不含系统提示词。
 * 带上视觉与语音能力标记，前端据此决定「上传图片」「说话」按钮是否可用、朗读走模型还是浏览器，
 * 避免用户传完图 / 说完话才在发送时被告知没有对应模型。
 */
@Data
public class AgentOptionResp {

    private Long id;
    private String name;
    private String code;
    private String runMode;

    /** 是否已绑定可用的 VISION 模型。false 时前端置灰上传按钮 */
    private Boolean visionEnabled;

    /** 是否已绑定可用的 ASR（语音识别）模型。false 时「按住说话」置灰并说明原因 */
    private Boolean asrEnabled;

    /**
     * 是否已绑定可用的 TTS（语音合成）模型。
     * false 时朗读仍可用（降级浏览器 speechSynthesis），只影响按钮提示与请求路径。
     */
    private Boolean ttsEnabled;
}
