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
    /** 业务唯一键；NULL 表示该条不参与去重（MySQL 唯一索引允许多个 NULL） */
    private String dedupeKey;
    private Long createdBy;
    private LocalDateTime createTime;
}
