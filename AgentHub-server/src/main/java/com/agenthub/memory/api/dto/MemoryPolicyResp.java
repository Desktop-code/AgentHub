package com.agenthub.memory.api.dto;

import lombok.Data;

/**
 * 记忆策略。所属域：memory。id 为空表示还没入库，展示的是平台默认。
 */
@Data
public class MemoryPolicyResp {

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
}
