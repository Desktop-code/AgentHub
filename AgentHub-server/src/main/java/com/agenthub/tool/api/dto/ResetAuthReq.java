package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置 MCP 鉴权头。所属域：tool。明文只出现在这次请求和对应响应里。
 */
@Data
public class ResetAuthReq {

    @NotBlank(message = "不能为空")
    @Size(max = 180, message = "最长 180")
    private String authHeader;
}
