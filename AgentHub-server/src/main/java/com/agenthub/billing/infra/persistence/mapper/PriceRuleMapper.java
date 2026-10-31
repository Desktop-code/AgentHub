package com.agenthub.billing.infra.persistence.mapper;

import com.agenthub.billing.infra.persistence.entity.PriceRuleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * billing_price_rule 映射。所属域：billing。
 */
@Mapper
public interface PriceRuleMapper extends BaseMapper<PriceRuleEntity> {
}
