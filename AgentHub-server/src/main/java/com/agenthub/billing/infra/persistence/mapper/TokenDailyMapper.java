package com.agenthub.billing.infra.persistence.mapper;

import com.agenthub.billing.infra.persistence.entity.TokenDailyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * billing_token_daily 映射。所属域：billing。
 */
@Mapper
public interface TokenDailyMapper extends BaseMapper<TokenDailyEntity> {
}
