package com.agenthub.billing.infra.persistence.mapper;

import com.agenthub.billing.infra.persistence.entity.TokenLedgerEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * billing_token_ledger 映射。所属域：billing。
 */
@Mapper
public interface TokenLedgerMapper extends BaseMapper<TokenLedgerEntity> {
}
