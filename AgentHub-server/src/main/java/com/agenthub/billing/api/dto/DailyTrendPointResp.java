package com.agenthub.billing.api.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 趋势点。所属域：billing。
 * 一天一个点，把当天所有维度（Agent / 模型 / 渠道）累加，供趋势图直接渲染。
 */
@Data
public class DailyTrendPointResp {

    /** 业务日 */
    private LocalDate bizDate;
    /** 当天输入 token 合计 */
    private Long tokenPrompt;
    /** 当天输出 token 合计 */
    private Long tokenCompletion;
    /** 当天 token 总量 */
    private Long tokenTotal;
}
