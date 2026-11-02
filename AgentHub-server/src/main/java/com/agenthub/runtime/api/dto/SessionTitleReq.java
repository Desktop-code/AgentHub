package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改试跑会话标题。所属域：runtime。写入 session_title，并把 title_source 置 MANUAL；
 * 列宽 64，与 TrialTexts.TITLE_LIMIT 一致，超长由服务端截断。
 */
@Data
public class SessionTitleReq {

    @NotBlank(message = "标题不能为空")
    @Size(max = 64, message = "标题不能超过 64 字")
    private String title;
}
