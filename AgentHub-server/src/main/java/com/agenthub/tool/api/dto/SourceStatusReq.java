package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 工具源启停。所属域：tool。1 正常，0 停用。
 */
@Data
public class SourceStatusReq {

    @NotNull(message = "不能为空")
    private Integer status;
}
