package com.agenthub.billing.domain.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单价规则。所属域：billing。对应 billing_price_rule。
 * modelInstanceId 为空表示默认规则。status 1 正常、0 停用。
 */
@Data
public class PriceRule {

    private Long id;
    private Long modelInstanceId;
    private String name;
    private BigDecimal pricePrompt;
    private BigDecimal priceCompletion;
    private String currency;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Integer status;
    private String remark;
}
