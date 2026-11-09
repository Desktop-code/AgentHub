package com.agenthub.billing.domain.repository;

import com.agenthub.billing.domain.model.LedgerQuery;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.common.api.PageResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
     * @throws org.springframework.dao.DuplicateKeyException dedupe_key 已存在（同一次计费事件重复写入）
     */
    Long insert(TokenLedger ledger, Long operatorId);

    /**
     * 按业务唯一键回查。写入撞唯一键时用它读回已有流水，实现「幂等成功」而不是二次计费。
     *
     * @param dedupeKey 业务唯一键
     * @return 已存在的流水；没有时为空
     */
    Optional<TokenLedger> findByDedupeKey(String dedupeKey);

    /**
     * 重算用。只取 status=1，避免作废行进入日汇总。
     *
     * @param dateFrom 含当天
     * @param dateTo   含当天
     * @return 该闭区间内的正常流水
     */
    List<TokenLedger> listNormal(LocalDate dateFrom, LocalDate dateTo);
}
