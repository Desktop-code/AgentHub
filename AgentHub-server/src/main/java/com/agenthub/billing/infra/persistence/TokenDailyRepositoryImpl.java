package com.agenthub.billing.infra.persistence;

import com.agenthub.billing.domain.model.DailyQuery;
import com.agenthub.billing.domain.model.DailyTotals;
import com.agenthub.billing.domain.model.TokenDaily;
import com.agenthub.billing.domain.repository.TokenDailyRepository;
import com.agenthub.billing.infra.persistence.entity.TokenDailyEntity;
import com.agenthub.billing.infra.persistence.mapper.TokenDailyMapper;
import com.agenthub.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 日汇总仓储实现。所属域：billing。Entity 不出本包。
 * 维度里的空 id 用 IS NULL 匹配，避免 MyBatis-Plus 把 eq(null) 吃掉。
 */
@Repository
public class TokenDailyRepositoryImpl implements TokenDailyRepository {

    private final TokenDailyMapper mapper;

    /**
     * @param mapper billing_token_daily
     */
    public TokenDailyRepositoryImpl(TokenDailyMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<TokenDaily> page(DailyQuery query) {
        LambdaQueryWrapper<TokenDailyEntity> wrapper = new LambdaQueryWrapper<TokenDailyEntity>()
                .orderByDesc(TokenDailyEntity::getBizDate)
                .orderByDesc(TokenDailyEntity::getId);
        if (query.getDateFrom() != null) {
            wrapper.ge(TokenDailyEntity::getBizDate, query.getDateFrom());
        }
        if (query.getDateTo() != null) {
            wrapper.le(TokenDailyEntity::getBizDate, query.getDateTo());
        }
        if (query.getAgentId() != null) {
            wrapper.eq(TokenDailyEntity::getAgentId, query.getAgentId());
        }
        if (query.getModelInstanceId() != null) {
            wrapper.eq(TokenDailyEntity::getModelInstanceId, query.getModelInstanceId());
        }
        if (query.getChannel() != null) {
            wrapper.eq(TokenDailyEntity::getChannel, query.getChannel());
        }
        IPage<TokenDailyEntity> page = mapper.selectPage(
                new Page<>(Math.max(query.getCurrent(), 1), Math.min(Math.max(query.getSize(), 1), 100)), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public List<TokenDaily> listRange(LocalDate dateFrom, LocalDate dateTo) {
        return mapper.selectList(new LambdaQueryWrapper<TokenDailyEntity>()
                        .ge(TokenDailyEntity::getBizDate, dateFrom)
                        .le(TokenDailyEntity::getBizDate, dateTo))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<TokenDaily> listForTrend(LocalDate dateFrom, LocalDate dateTo, Long agentId,
                                         Long modelInstanceId, String channel) {
        LambdaQueryWrapper<TokenDailyEntity> wrapper = new LambdaQueryWrapper<TokenDailyEntity>()
                .ge(TokenDailyEntity::getBizDate, dateFrom)
                .le(TokenDailyEntity::getBizDate, dateTo)
                .orderByAsc(TokenDailyEntity::getBizDate);
        // 维度条件只在传值时收窄，未传表示全部；空值一律不下推到 SQL
        if (agentId != null) {
            wrapper.eq(TokenDailyEntity::getAgentId, agentId);
        }
        if (modelInstanceId != null) {
            wrapper.eq(TokenDailyEntity::getModelInstanceId, modelInstanceId);
        }
        if (channel != null) {
            wrapper.eq(TokenDailyEntity::getChannel, channel);
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(DailyTotals totals, Long operatorId) {
        TokenDailyEntity entity = new TokenDailyEntity();
        entity.setBizDate(totals.getBizDate());
        entity.setAgentId(totals.getAgentId());
        entity.setModelInstanceId(totals.getModelInstanceId());
        entity.setAppId(totals.getAppId());
        entity.setChannel(totals.getChannel());
        entity.setTokenPrompt(totals.getTokenPrompt());
        entity.setTokenCompletion(totals.getTokenCompletion());
        entity.setTokenTotal(totals.getTokenTotal());
        entity.setAmount(totals.getAmount());
        entity.setCurrency(totals.getCurrency());
        entity.setStatus(1);
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateTotals(Long id, DailyTotals totals, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<TokenDailyEntity>()
                .eq(TokenDailyEntity::getId, id)
                .set(TokenDailyEntity::getTokenPrompt, totals.getTokenPrompt())
                .set(TokenDailyEntity::getTokenCompletion, totals.getTokenCompletion())
                .set(TokenDailyEntity::getTokenTotal, totals.getTokenTotal())
                .set(TokenDailyEntity::getAmount, totals.getAmount())
                .set(TokenDailyEntity::getCurrency, totals.getCurrency())
                .set(TokenDailyEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void clearTotals(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<TokenDailyEntity>()
                .eq(TokenDailyEntity::getId, id)
                .set(TokenDailyEntity::getTokenPrompt, 0L)
                .set(TokenDailyEntity::getTokenCompletion, 0L)
                .set(TokenDailyEntity::getTokenTotal, 0L)
                .set(TokenDailyEntity::getAmount, null)
                .set(TokenDailyEntity::getCurrency, null)
                .set(TokenDailyEntity::getUpdatedBy, operatorId));
    }

    private TokenDaily toDomain(TokenDailyEntity entity) {
        TokenDaily daily = new TokenDaily();
        daily.setId(entity.getId());
        daily.setBizDate(entity.getBizDate());
        daily.setAgentId(entity.getAgentId());
        daily.setModelInstanceId(entity.getModelInstanceId());
        daily.setAppId(entity.getAppId());
        daily.setChannel(entity.getChannel());
        daily.setTokenPrompt(entity.getTokenPrompt());
        daily.setTokenCompletion(entity.getTokenCompletion());
        daily.setTokenTotal(entity.getTokenTotal());
        daily.setAmount(entity.getAmount());
        daily.setCurrency(entity.getCurrency());
        daily.setStatus(entity.getStatus());
        // 域/API 仍用 createTime；库列已是 create_time
        daily.setCreateTime(entity.getCreateTime());
        return daily;
    }
}
