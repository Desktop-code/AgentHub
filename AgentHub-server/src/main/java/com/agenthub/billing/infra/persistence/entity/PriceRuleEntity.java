package com.agenthub.billing.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * billing_price_rule。所属域：billing。列与 sql/schema-delta-billing.sql 一致。
 * modelInstanceId 为空表示默认规则。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_price_rule")
public class PriceRuleEntity extends BillingAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelInstanceId;
    private String name;
    private BigDecimal pricePrompt;
    private BigDecimal priceCompletion;
    private String currency;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    /** 1 正常，0 停用 */
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;
}
