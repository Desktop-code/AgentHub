package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑 MCP 工具源资料。所属域：tool。不含鉴权头和启停，那两项走单独接口。
 */
@Data
public class SourceUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    /** SSE 或 HTTP（Streamable HTTP）；空则后端按 SSE */
    @Size(max = 32, message = "最长 32")
    private String transport;

    @NotBlank(message = "不能为空")
    @Size(max = 512, message = "最长 512")
    private String baseUrl;

    @Size(max = 500, message = "最长 500")
    private String remark;
}
