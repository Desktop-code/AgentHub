package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * MCP 链接探测入参。所属域：tool。
 * 新建弹窗只填地址与可选鉴权头；编辑时可带 sourceId，鉴权头留空则用库中密文解密。
 */
@Data
public class SourceProbeReq {

    /** 已存在源 id；编辑探测且未填新鉴权头时用 */
    private Long sourceId;

    @Size(max = 32, message = "最长 32")
    private String transport;

    @Size(max = 512, message = "最长 512")
    private String baseUrl;

    @Size(max = 180, message = "最长 180")
    private String authHeader;
}
