package com.agenthub.admin.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 应用可调用的 Agent。所属域：admin。只保存 id，不校验 Agent 表。
 */
@Data
public class AppAgentReq {

    @NotNull(message = "授权列表不能为空")
    private List<Long> agentIds;
}
