package com.agenthub.memory.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 记忆启停。所属域：memory。1 启用，0 停用。
 */
@Data
public class MemoryStatusReq {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
