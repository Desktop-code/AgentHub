package com.agenthub.eval.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 坏案（跨 run 结果）分页查询。所属域：eval。
 * <p>默认只查 FAIL；{@code includeError=true} 时并入 ERROR（仅展示，promote 仍默认仅 FAIL）。
 */
@Data
public class EvalBadCasePageReq {

    @Min(1)
    private long current = 1;

    @Min(1)
    @Max(100)
    private long size = 20;

    /** 是否并入 ERROR 行；默认 false */
    private Boolean includeError;
}
