package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Agent 启停。所属域：agent。1 正常，0 停用。
 */
@Data
public class AgentStatusReq {

    @NotNull(message = "不能为空")
    private Integer status;
}
