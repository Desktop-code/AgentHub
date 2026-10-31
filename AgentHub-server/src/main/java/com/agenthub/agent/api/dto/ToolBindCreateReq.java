package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增工具绑定。所属域：agent。工具项必须已启用。
 */
@Data
public class ToolBindCreateReq {

    @NotNull(message = "不能为空")
    private Long toolItemId;

    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;
}
