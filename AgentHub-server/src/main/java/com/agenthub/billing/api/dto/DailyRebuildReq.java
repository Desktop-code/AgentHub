package com.agenthub.billing.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 重算日汇总。所属域：billing。闭区间，两端都要有。
 */
@Data
public class DailyRebuildReq {

    @NotNull(message = "开始日期不能为空")
    private LocalDate dateFrom;

    @NotNull(message = "结束日期不能为空")
    private LocalDate dateTo;
}
