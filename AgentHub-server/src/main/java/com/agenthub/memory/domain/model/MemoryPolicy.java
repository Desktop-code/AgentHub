package com.agenthub.memory.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 的记忆与窗口策略。所属域：memory。对应 memory_agent_policy。
 * 每个 Agent 至多一条有效行。窗口和 TTL 字段生效时不超过 YAML 硬顶。
 */
@Data
public class MemoryPolicy {

    private Long id;
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
    private Integer status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
