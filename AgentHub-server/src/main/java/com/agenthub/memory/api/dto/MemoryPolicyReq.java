package com.agenthub.memory.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存记忆策略。所属域：memory。数值上限由服务按 YAML 硬顶再校验。
 */
@Data
public class MemoryPolicyReq {

    @NotNull(message = "Agent 不能为空")
    private Long agentId;
    private Integer enableShort;
    private Integer enableLong;
    private Integer maxShortItems;
    private Integer maxLongItems;
    private Integer ctxMaxTurns;
    private Integer ctxMaxChars;
    private Integer ctxIdleTtlSec;
    private Integer shortDefaultTtlSec;
    private Long summaryModelInstanceId;
    private Integer enableLongExtract;
    private Long longExtractModelInstanceId;
    private Integer longExtractOnSessionEnd;
    private Integer longExtractOnExplicit;

    @Size(max = 500, message = "备注最长 500")
    private String remark;
}
