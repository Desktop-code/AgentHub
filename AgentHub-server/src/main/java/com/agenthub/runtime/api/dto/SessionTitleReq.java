package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改试跑会话标题。所属域：runtime。写入 last_msg_preview，不另加列。
 */
@Data
public class SessionTitleReq {

    @NotBlank(message = "标题不能为空")
    @Size(max = 80, message = "标题不能超过 80 字")
    private String title;
}
