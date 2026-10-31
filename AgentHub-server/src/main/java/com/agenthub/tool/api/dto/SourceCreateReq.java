package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 MCP 工具源。所属域：tool。鉴权头可选，只在这次请求里出现明文。
 */
@Data
public class SourceCreateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @Size(max = 32, message = "最长 32")
    private String type;

    @Size(max = 32, message = "最长 32")
    private String transport;

    @NotBlank(message = "不能为空")
    @Size(max = 512, message = "最长 512")
    private String baseUrl;

    @Size(max = 180, message = "最长 180")
    private String authHeader;

    @Size(max = 500, message = "最长 500")
    private String remark;

    private Integer status;
}
