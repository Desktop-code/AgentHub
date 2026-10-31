package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 工具项启停。所属域：tool。1 进入白名单，0 移出。
 */
@Data
public class ItemStatusReq {

    @NotNull(message = "不能为空")
    private Integer status;
}
