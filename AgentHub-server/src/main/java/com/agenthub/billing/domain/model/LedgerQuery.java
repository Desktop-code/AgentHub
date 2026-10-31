package com.agenthub.billing.domain.model;

import lombok.Data;

import java.time.LocalDate;

/**
 * 流水分页条件。所属域：billing。日期、Agent、模型和渠道都可空。
 */
@Data
public class LedgerQuery {

    private long current = 1;
    private long size = 20;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Long agentId;
    private Long modelInstanceId;
    private String channel;
    /** 测试和排查用，页面不展示 */
    private Long sessionId;
}
