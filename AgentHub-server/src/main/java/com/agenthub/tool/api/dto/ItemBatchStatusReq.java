package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 源内批量启停工具项入参。所属域：tool。
 * sourceId 必填且只接受 MCP 源；status 只接受 1（全部启用）/ 0（全部停用）。
 */
@Data
public class ItemBatchStatusReq {

    @NotNull(message = "工具源不能为空")
    private Long sourceId;

    @NotNull(message = "不能为空")
    private Integer status;
}
