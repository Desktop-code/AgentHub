package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 方案启停请求。所属域：agent。
 *
 * <p>启停单独走接口，避免「整份保存」时误把停用方案顺手启用。
 * 启用时会再次校验同一绑根是否已有其它启用方案。
 */
@Data
public class MaDefEnabledReq {

    @NotNull(message = "启停状态不能为空")
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer enabled;
}
