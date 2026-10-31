package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 实例启停。所属域：model。1 正常，0 停用。
 */
@Data
public class InstanceStatusReq {

    @NotNull(message = "不能为空")
    private Integer status;
}
