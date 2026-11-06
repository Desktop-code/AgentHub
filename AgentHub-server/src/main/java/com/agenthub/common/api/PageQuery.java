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

    /** 单页条数硬上限；与 {@link #size} 上的 {@code @Max} 保持一致 */
    public static final long MAX_SIZE = 100;

    /**
     * 页码夹取：非正数一律按第 1 页。
     *
     * <p>入参已由 {@code @Min} 校验，但仓储层仍会被内部调用（评测、工具、批处理）直接传入，
     * 这里兜住 0 / 负数，避免 MySQL {@code LIMIT} 偏移算错。
     *
     * @param current 期望页码
     * @return 不小于 1 的页码
     */
    public static long clampCurrent(long current) {
        return Math.max(current, 1);
    }

    /**
     * 单页条数夹取：1..{@link #MAX_SIZE}。
     *
     * @param size 期望每页条数
     * @return 落在 [1, 100] 的条数
     */
    public static long clampSize(long size) {
        return clampSize(size, MAX_SIZE);
    }

    /**
     * 单页条数夹取：1..max，用于 workflow 运行记录等「另立上限」的查询。
     *
     * @param size 期望每页条数
     * @param max  该查询自己的上限；调用方保证 &gt; 0
     * @return 落在 [1, max] 的条数
     */
    public static long clampSize(long size, long max) {
        return Math.min(Math.max(size, 1), max);
    }
}
