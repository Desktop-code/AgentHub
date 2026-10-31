package com.agenthub.billing.infra.persistence;

import com.agenthub.billing.domain.model.LedgerQuery;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.billing.domain.repository.TokenLedgerRepository;
import com.agenthub.billing.infra.persistence.entity.TokenLedgerEntity;
import com.agenthub.billing.infra.persistence.mapper.TokenLedgerMapper;
import com.agenthub.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 流水仓储实现。所属域：billing。Entity 不出本包。
 */
@Repository
public class TokenLedgerRepositoryImpl implements TokenLedgerRepository {

    private final TokenLedgerMapper mapper;

    /**
     * @param mapper billing_token_ledger
     */
    public TokenLedgerRepositoryImpl(TokenLedgerMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<TokenLedger> page(LedgerQuery query) {
        LambdaQueryWrapper<TokenLedgerEntity> wrapper = new LambdaQueryWrapper<TokenLedgerEntity>()
                .orderByDesc(TokenLedgerEntity::getId);
        applyFilter(wrapper, query);
        IPage<TokenLedgerEntity> page = mapper.selectPage(new Page<>(page(query.getCurrent()), size(query.getSize())), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(TokenLedger ledger, Long operatorId) {
        TokenLedgerEntity entity = new TokenLedgerEntity();
        entity.setBizDate(ledger.getBizDate());
        entity.setAgentId(ledger.getAgentId());
        entity.setModelInstanceId(ledger.getModelInstanceId());
        entity.setAppId(ledger.getAppId());
        entity.setSessionId(ledger.getSessionId());
        entity.setChannel(ledger.getChannel());
        entity.setTokenPrompt(ledger.getTokenPrompt());
        entity.setTokenCompletion(ledger.getTokenCompletion());
        entity.setTokenTotal(ledger.getTokenTotal());
        entity.setUnitPricePrompt(ledger.getUnitPricePrompt());
        entity.setUnitPriceCompletion(ledger.getUnitPriceCompletion());
        entity.setAmount(ledger.getAmount());
        entity.setCurrency(ledger.getCurrency());
        entity.setStatus(ledger.getStatus());
        entity.setRemark(ledger.getRemark());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * 只取正常流水。作废行即使以后出现也不进日汇总。
     */
    @Override
    public List<TokenLedger> listNormal(LocalDate dateFrom, LocalDate dateTo) {
        List<TokenLedgerEntity> rows = mapper.selectList(new LambdaQueryWrapper<TokenLedgerEntity>()
                .ge(TokenLedgerEntity::getBizDate, dateFrom)
                .le(TokenLedgerEntity::getBizDate, dateTo)
                .eq(TokenLedgerEntity::getStatus, 1)
                .orderByAsc(TokenLedgerEntity::getId));
        return rows.stream().map(this::toDomain).toList();
    }

    private void applyFilter(LambdaQueryWrapper<TokenLedgerEntity> wrapper, LedgerQuery query) {
        if (query.getDateFrom() != null) {
            wrapper.ge(TokenLedgerEntity::getBizDate, query.getDateFrom());
        }
        if (query.getDateTo() != null) {
            wrapper.le(TokenLedgerEntity::getBizDate, query.getDateTo());
        }
        if (query.getAgentId() != null) {
            wrapper.eq(TokenLedgerEntity::getAgentId, query.getAgentId());
        }
        if (query.getModelInstanceId() != null) {
            wrapper.eq(TokenLedgerEntity::getModelInstanceId, query.getModelInstanceId());
        }
        if (query.getChannel() != null) {
            wrapper.eq(TokenLedgerEntity::getChannel, query.getChannel());
        }
        if (query.getSessionId() != null) {
            wrapper.eq(TokenLedgerEntity::getSessionId, query.getSessionId());
        }
    }

    private TokenLedger toDomain(TokenLedgerEntity entity) {
        TokenLedger ledger = new TokenLedger();
        ledger.setId(entity.getId());
        ledger.setBizDate(entity.getBizDate());
        ledger.setAgentId(entity.getAgentId());
        ledger.setModelInstanceId(entity.getModelInstanceId());
        ledger.setAppId(entity.getAppId());
        ledger.setSessionId(entity.getSessionId());
        ledger.setChannel(entity.getChannel());
        ledger.setTokenPrompt(entity.getTokenPrompt());
        ledger.setTokenCompletion(entity.getTokenCompletion());
        ledger.setTokenTotal(entity.getTokenTotal());
        ledger.setUnitPricePrompt(entity.getUnitPricePrompt());
        ledger.setUnitPriceCompletion(entity.getUnitPriceCompletion());
        ledger.setAmount(entity.getAmount());
        ledger.setCurrency(entity.getCurrency());
        ledger.setStatus(entity.getStatus());
        ledger.setRemark(entity.getRemark());
        ledger.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        ledger.setCreateTime(entity.getCreateTime());
        return ledger;
    }

    private long page(long current) {
        return Math.max(current, 1);
    }

    private long size(long size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
