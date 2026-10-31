package com.agenthub.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页出参。所属域：common。字段名与前端表格约定一致。
 *
 * @param <T> 行数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private long total;
    private long current;
    private long size;
    private List<T> records;

    /**
     * 组装一页数据。
     *
     * @param total   总行数
     * @param current 当前页，从 1 开始
     * @param size    本页大小
     * @param records 本页记录，不为 null
     * @param <T>     行类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(long total, long current, long size, List<T> records) {
        return new PageResult<>(total, current, size, records);
    }
}
