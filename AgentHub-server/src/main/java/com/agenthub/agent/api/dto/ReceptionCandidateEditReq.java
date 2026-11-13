package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改默认接待候选的启停。所属域：agent。
 *
 * <p>意图说明已不可改：它只来自 Agent 简介（{@code agent_def.description}）。
 * {@code intentDesc} 字段保留只为让旧前端拿到<b>明确失败</b>，而不是静默忽略。
 */
@Data
public class ReceptionCandidateEditReq {

    /**
     * 已废止：传了就拒绝（400），提示去 Agent 管理改简介。
     */
    @Deprecated
    @Size(max = 512, message = "意图说明最长 512")
    private String intentDesc;

    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer enabled;
}
