package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新建试跑会话。所属域：runtime。
 */
@Data
public class SessionCreateReq {

    @NotNull(message = "请选择 Agent")
    private Long agentId;
}
