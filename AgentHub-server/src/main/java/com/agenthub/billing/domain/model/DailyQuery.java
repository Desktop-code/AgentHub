package com.agenthub.billing.domain.model;

import lombok.Data;

import java.time.LocalDate;

/**
 * 日汇总查询条件。所属域：billing。维度筛选都可空。
 */
@Data
public class DailyQuery {

    private long current = 1;
    private long size = 20;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Long agentId;
    private Long modelInstanceId;
    private String channel;
}
