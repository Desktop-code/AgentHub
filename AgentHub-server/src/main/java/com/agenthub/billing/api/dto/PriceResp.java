package com.agenthub.billing.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单价行。所属域：billing。
 */
@Data
public class PriceResp {

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
