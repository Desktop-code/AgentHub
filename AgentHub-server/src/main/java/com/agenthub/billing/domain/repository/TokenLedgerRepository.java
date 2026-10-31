package com.agenthub.billing.domain.repository;

import com.agenthub.billing.domain.model.LedgerQuery;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.common.api.PageResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 流水仓储。所属域：billing。实现不得把 Entity 交回应用层。
 */
public interface TokenLedgerRepository {

    /**
     * @param query 分页和可选筛选
     * @return 一页流水，含作废行
     */
    PageResult<TokenLedger> page(LedgerQuery query);

    /**
     * @param ledger     新流水，status 由调用方给定
     * @param operatorId 创建人，可空
     * @return 新主键
     */
    Long insert(TokenLedger ledger, Long operatorId);

    /**
     * 重算用。只取 status=1，避免作废行进入日汇总。
     *
     * @param dateFrom 含当天
     * @param dateTo   含当天
     * @return 该闭区间内的正常流水
     */
    List<TokenLedger> listNormal(LocalDate dateFrom, LocalDate dateTo);
}
