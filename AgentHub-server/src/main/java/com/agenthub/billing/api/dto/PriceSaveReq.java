package com.agenthub.billing.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新建或编辑单价。所属域：billing。modelInstanceId 空表示默认规则。启停不走这个对象。
 */
@Data
public class PriceSaveReq {

    private Long modelInstanceId;

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64")
    private String name;

    @NotNull(message = "prompt 单价不能为空")
    private BigDecimal pricePrompt;

    @NotNull(message = "completion 单价不能为空")
    private BigDecimal priceCompletion;

    @Size(max = 8, message = "币种最长 8")
    private String currency;

    @NotNull(message = "生效开始不能为空")
    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    @Size(max = 500, message = "备注最长 500")
    private String remark;
}
