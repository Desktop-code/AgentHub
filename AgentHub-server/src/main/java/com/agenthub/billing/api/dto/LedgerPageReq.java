package com.agenthub.billing.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 流水分页。所属域：billing。筛选都可空。sessionId 只给排查，页面不展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LedgerPageReq extends PageQuery {

    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Long agentId;
    private Long modelInstanceId;
    private String channel;
    private Long sessionId;
}
