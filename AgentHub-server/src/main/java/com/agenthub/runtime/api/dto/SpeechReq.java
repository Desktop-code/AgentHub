package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 语音合成请求。所属域：runtime。
 * text 由前端把「最终回答」转成纯文本后提交；思考过程与工具原文不在这里出现。
 */
@Data
public class SpeechReq {

    /** 用哪个 Agent 的 TTS 槽；语音模型不给平台兜底，必须落到具体 Agent */
    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    /**
     * 待朗读文字。上限放到 8000 只是入参防爆；
     * 真正的业务上限见 agenthub.runtime.voice.max-speech-chars（默认 4000）。
     */
    @NotBlank(message = "朗读内容不能为空")
    @Size(max = 8000, message = "朗读内容过长")
    private String text;
}
