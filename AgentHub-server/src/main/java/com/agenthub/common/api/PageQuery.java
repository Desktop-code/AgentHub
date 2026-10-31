package com.agenthub.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页入参。所属域：common。全项目统一 current/size，size 上限 100，默认 10。
 */
@Data
public class PageQuery {

    @Min(value = 1, message = "页码从 1 开始")
    private long current = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = DEFAULT_SIZE;

    /** 全站默认每页条数；控制台下拉提供 10/20/50/100 */
    public static final long DEFAULT_SIZE = 10;
}
