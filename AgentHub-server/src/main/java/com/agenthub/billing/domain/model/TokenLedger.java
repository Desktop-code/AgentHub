package com.agenthub.billing.domain.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一条 Token 流水。所属域：billing。对应 billing_token_ledger。
 * status 1 正常、0 作废；作废不物理删除。
 */
@Data
public class TokenLedger {

    private Long id;
    private LocalDate bizDate;
    private Long agentId;
    private Long modelInstanceId;
    private Long appId;
    private Long sessionId;
    private String channel;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private Integer tokenTotal;
    private BigDecimal unitPricePrompt;
    private BigDecimal unitPriceCompletion;
    private BigDecimal amount;
    private String currency;
    private Integer status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
}
