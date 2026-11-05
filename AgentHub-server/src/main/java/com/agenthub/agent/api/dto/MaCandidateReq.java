package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 方案内单个候选的保存请求。所属域：agent。
 *
 * <p>route_key 可空：空则由服务端按子 Agent code 生成（冲突回落 child_{id}）；
 * 意图描述必填，直接喂给判路由 LLM 或作为串行步骤说明。
 */
@Data
public class MaCandidateReq {

    @NotNull(message = "候选子 Agent 不能为空")
    private Long agentId;

    /** 可空；空则按子 Agent code 生成，同方案内唯一 */
    @Size(max = 64, message = "最长 64")
    private String routeKey;

    @NotBlank(message = "候选意图描述不能为空")
    @Size(max = 512, message = "最长 512")
    private String intentDesc;

    /** 可空；空则按同方案 max(sort_no)+10 生成 */
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 10000, message = "不能大于 10000")
    private Integer sortNo;

    /** 可空，默认 1 启用 */
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer enabled;
}
