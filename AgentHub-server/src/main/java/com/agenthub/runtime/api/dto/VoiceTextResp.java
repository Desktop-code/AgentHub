package com.agenthub.runtime.api.dto;

import lombok.Data;

/**
 * 语音识别结果。所属域：runtime。
 * 只回文字，不回音频、不回上游原始报文。
 */
@Data
public class VoiceTextResp {

    /** 识别出的文字；后端已保证非空（空结果会转成业务错误） */
    private String text;
}
