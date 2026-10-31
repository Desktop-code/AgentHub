package com.agenthub.billing.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日汇总行。所属域：billing。
 */
@Data
public class DailyResp {

    private Long id;
    private LocalDate bizDate;
    private Long agentId;
    /** 展示用；Agent 已删或不存在时为空，前端回退 #id */
    private String agentName;
    private Long modelInstanceId;
    /**
     * 模型实例展示名。有行用真名；查无行时为「已删除模型」。
     * 删/停状态见 modelDeleted / modelDisabled，前端旁挂 Tag。
     */
    private String modelInstanceName;
    /** 供应商名；与实例名拼成「供应商 / 实例名」 */
    private String providerName;
    /** true：已软删或不存在；前端禁再拉详情；与 modelDisabled 互斥（删优先） */
    private Boolean modelDeleted;
    /** true：未删且 status=0；前端打「已停用」Tag */
    private Boolean modelDisabled;
    private Long appId;
    private String channel;
    private Long tokenPrompt;
    private Long tokenCompletion;
    private Long tokenTotal;
    private BigDecimal amount;
    private String currency;
    private Integer status;
}
