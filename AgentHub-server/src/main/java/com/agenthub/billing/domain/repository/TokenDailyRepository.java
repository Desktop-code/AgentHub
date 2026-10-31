package com.agenthub.billing.domain.repository;

import com.agenthub.billing.domain.model.DailyQuery;
import com.agenthub.billing.domain.model.DailyTotals;
import com.agenthub.billing.domain.model.TokenDaily;
import com.agenthub.common.api.PageResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 日汇总仓储。所属域：billing。同维覆盖在应用层完成，表上没有唯一键。
 */
public interface TokenDailyRepository {

    /**
     * @param query 分页和可选维度
     * @return 一页日汇总
     */
    PageResult<TokenDaily> page(DailyQuery query);

    /**
     * @param dateFrom 含当天
     * @param dateTo   含当天
     * @return 区间内已有汇总，供重算覆盖
     */
    List<TokenDaily> listRange(LocalDate dateFrom, LocalDate dateTo);

    /**
     * 趋势图取数：区间内按可选维度过滤的全量行（不分页）。
     * 调用方按业务日再聚合成一天一点，因此这里返回明细维度的原始行。
     *
     * @param dateFrom        含当天，非空
     * @param dateTo          含当天，非空
     * @param agentId         Agent 过滤，可空
     * @param modelInstanceId 模型实例过滤，可空
     * @param channel         渠道过滤，可空
     * @return 区间内的日汇总行
     */
    List<TokenDaily> listForTrend(LocalDate dateFrom, LocalDate dateTo, Long agentId,
                                  Long modelInstanceId, String channel);

    /**
     * @param totals     新维度合计
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(DailyTotals totals, Long operatorId);

    /**
     * 覆盖已有同维行的 token 和金额，不改 status。
     *
     * @param id         日汇总主键
     * @param totals     重算结果
     * @param operatorId 更新人
     */
    void updateTotals(Long id, DailyTotals totals, Long operatorId);

    /**
     * 该维在本次重算里已经没有正常流水，把数量清零，避免旧数残留。
     *
     * @param id         日汇总主键
     * @param operatorId 更新人
     */
    void clearTotals(Long id, Long operatorId);
}
