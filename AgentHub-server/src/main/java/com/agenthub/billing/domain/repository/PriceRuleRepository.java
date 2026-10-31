package com.agenthub.billing.domain.repository;

import com.agenthub.billing.domain.model.PriceRule;
import com.agenthub.common.api.PageResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 单价仓储。所属域：billing。删除走逻辑删，不物理删行。
 */
public interface PriceRuleRepository {

    /**
     * @param current 页码，从 1 开始
     * @param size    每页条数，上限 100
     * @return 一页规则
     */
    PageResult<PriceRule> page(long current, long size);

    /**
     * @param id 主键
     * @return 未删除的规则
     */
    Optional<PriceRule> findById(Long id);

    /**
     * 当前时刻可能命中的规则：该模型实例，以及 modelInstanceId 为空的默认规则。
     *
     * @param modelInstanceId 本步模型，可空
     * @param at              调用时间
     * @return 启用且落在生效期内的候选
     */
    List<PriceRule> listCandidates(Long modelInstanceId, LocalDateTime at);

    /**
     * @param rule       新规则
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(PriceRule rule, Long operatorId);

    /**
     * 更新名称、单价和生效期，不改启停。
     *
     * @param rule       含主键
     * @param operatorId 更新人
     */
    void updateProfile(PriceRule rule, Long operatorId);

    /**
     * @param id         主键
     * @param status     1 正常，0 停用
     * @param operatorId 更新人
     */
    void updateStatus(Long id, int status, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 更新人
     */
    void logicalDelete(Long id, Long operatorId);
}
