package com.agenthub.billing.domain.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一日汇总。所属域：billing。对应 billing_token_daily。
 * 维度是业务日 + agent + 模型实例 + app（可空）+ channel。
 */
@Data
public class TokenDaily {

    private Long id;
    private LocalDate bizDate;
    private Long agentId;
    private Long modelInstanceId;
    private Long appId;
    private String channel;
    private Long tokenPrompt;
    private Long tokenCompletion;
    private Long tokenTotal;
    private BigDecimal amount;
    private String currency;
    private Integer status;
    private LocalDateTime createTime;
}
