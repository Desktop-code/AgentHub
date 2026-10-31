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
 * billing_token_daily。所属域：billing。列与 sql/schema-delta-billing.sql 一致。
 * token 用 BIGINT，和流水的 INT 不同。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_token_daily")
public class TokenDailyEntity extends BillingAuditEntity {

    @TableId(type = IdType.AUTO)
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
    /** 1 正常，0 停用。重算只覆盖数量 */
    private Integer status;

    @TableLogic
    private Integer deleted;
}
