package com.agenthub.billing.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 日汇总分页。所属域：billing。筛选都可空。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DailyPageReq extends PageQuery {

    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Long agentId;
    private Long modelInstanceId;
    private String channel;
}
