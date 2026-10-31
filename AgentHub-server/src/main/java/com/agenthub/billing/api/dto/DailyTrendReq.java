package com.agenthub.billing.api.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 趋势查询入参。所属域：billing。
 * 不继承分页：趋势按天聚合成一条线，不分页。日期缺省时由服务端取最近 7 天。
 */
@Data
public class DailyTrendReq {

    /** 起始业务日（含），可空 */
    private LocalDate dateFrom;
    /** 结束业务日（含），可空 */
    private LocalDate dateTo;
    /** 只统计该 Agent，可空表示全部 */
    private Long agentId;
    /** 只统计该模型实例，可空表示全部 */
    private Long modelInstanceId;
    /** 只统计该渠道（CONSOLE / APP_API），可空表示全部 */
    private String channel;
}
