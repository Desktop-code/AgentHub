package com.agenthub.eval.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 跑批列表查询。所属域：eval。
 */
@Data
public class EvalRunPageReq {

    @Min(1)
    private long current = 1;

    @Min(1)
    @Max(100)
    private long size = 20;

    /** 套件筛选，可空 */
    private Long suiteId;
    /** Agent 筛选，可空 */
    private Long agentId;
    /** 状态筛选：PENDING|RUNNING|…，可空 */
    private String status;
}
