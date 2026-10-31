package com.agenthub.billing.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * billing_token_ledger。所属域：billing。列与 sql/schema-delta-billing.sql 一致。
 * 本期不增加 turn_id、message_id，也不区分 rerank/embed。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_token_ledger")
public class TokenLedgerEntity extends BillingAuditEntity {

    @TableId(type = IdType.AUTO)
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
    /** 1 正常。本期写入恒为 1，不作废 */
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;
}
