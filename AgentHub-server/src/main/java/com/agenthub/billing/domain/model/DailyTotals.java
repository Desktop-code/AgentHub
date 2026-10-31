package com.agenthub.billing.domain.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一次重算得到的同维合计。所属域：billing。还没落日表。
 */
@Data
public class DailyTotals {

    private LocalDate bizDate;
    private Long agentId;
    private Long modelInstanceId;
    private Long appId;
    private String channel;
    private long tokenPrompt;
    private long tokenCompletion;
    private long tokenTotal;
    private BigDecimal amount;
    private String currency;
}
