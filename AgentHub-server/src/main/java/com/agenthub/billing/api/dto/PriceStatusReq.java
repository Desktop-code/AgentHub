package com.agenthub.billing.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单价启停。所属域：billing。1 正常，0 停用。
 */
@Data
public class PriceStatusReq {

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;
}
