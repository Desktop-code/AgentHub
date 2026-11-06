package com.agenthub.billing.infra.persistence;

import com.agenthub.billing.domain.model.PriceRule;
import com.agenthub.billing.domain.repository.PriceRuleRepository;
import com.agenthub.billing.infra.persistence.entity.PriceRuleEntity;
import com.agenthub.billing.infra.persistence.mapper.PriceRuleMapper;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 单价仓储实现。所属域：billing。Entity 不出本包。
 */
@Repository
public class PriceRuleRepositoryImpl implements PriceRuleRepository {

    private final PriceRuleMapper mapper;

    /**
     * @param mapper billing_price_rule
     */
    public PriceRuleRepositoryImpl(PriceRuleMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<PriceRule> page(long current, long size) {
        IPage<PriceRuleEntity> page = mapper.selectPage(
                new Page<>(PageQuery.clampCurrent(current), PageQuery.clampSize(size)),
                new LambdaQueryWrapper<PriceRuleEntity>().orderByDesc(PriceRuleEntity::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PriceRule> findById(Long id) {
        PriceRuleEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * {@inheritDoc}
     * 模型实例为空时只取默认规则，避免把别的实例价算进来。
     */
    @Override
    public List<PriceRule> listCandidates(Long modelInstanceId, LocalDateTime at) {
        LambdaQueryWrapper<PriceRuleEntity> wrapper = new LambdaQueryWrapper<PriceRuleEntity>()
                .eq(PriceRuleEntity::getStatus, 1)
                .le(PriceRuleEntity::getEffectiveFrom, at)
                .and(one -> one.isNull(PriceRuleEntity::getEffectiveTo).or().ge(PriceRuleEntity::getEffectiveTo, at));
        if (modelInstanceId == null) {
            wrapper.isNull(PriceRuleEntity::getModelInstanceId);
        } else {
            wrapper.and(one -> one.eq(PriceRuleEntity::getModelInstanceId, modelInstanceId)
                    .or().isNull(PriceRuleEntity::getModelInstanceId));
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(PriceRule rule, Long operatorId) {
        PriceRuleEntity entity = new PriceRuleEntity();
        copy(entity, rule);
        entity.setStatus(rule.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * modelInstanceId 允许改回空，表示改成默认规则，所以这里显式 set null。
     */
    @Override
    public void updateProfile(PriceRule rule, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PriceRuleEntity>()
                .eq(PriceRuleEntity::getId, rule.getId())
                .set(PriceRuleEntity::getModelInstanceId, rule.getModelInstanceId())
                .set(PriceRuleEntity::getName, rule.getName())
                .set(PriceRuleEntity::getPricePrompt, rule.getPricePrompt())
                .set(PriceRuleEntity::getPriceCompletion, rule.getPriceCompletion())
                .set(PriceRuleEntity::getCurrency, rule.getCurrency())
                .set(PriceRuleEntity::getEffectiveFrom, rule.getEffectiveFrom())
                .set(PriceRuleEntity::getEffectiveTo, rule.getEffectiveTo())
                .set(PriceRuleEntity::getRemark, rule.getRemark())
                .set(PriceRuleEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(Long id, int status, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PriceRuleEntity>()
                .eq(PriceRuleEntity::getId, id)
                .set(PriceRuleEntity::getStatus, status)
                .set(PriceRuleEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 先记下更新人，再逻辑删除。
     */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PriceRuleEntity>()
                .eq(PriceRuleEntity::getId, id)
                .set(PriceRuleEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    private void copy(PriceRuleEntity entity, PriceRule rule) {
        entity.setModelInstanceId(rule.getModelInstanceId());
        entity.setName(rule.getName());
        entity.setPricePrompt(rule.getPricePrompt());
        entity.setPriceCompletion(rule.getPriceCompletion());
        entity.setCurrency(rule.getCurrency());
        entity.setEffectiveFrom(rule.getEffectiveFrom());
        entity.setEffectiveTo(rule.getEffectiveTo());
        entity.setRemark(rule.getRemark());
    }

    private PriceRule toDomain(PriceRuleEntity entity) {
        PriceRule rule = new PriceRule();
        rule.setId(entity.getId());
        rule.setModelInstanceId(entity.getModelInstanceId());
        rule.setName(entity.getName());
        rule.setPricePrompt(entity.getPricePrompt());
        rule.setPriceCompletion(entity.getPriceCompletion());
        rule.setCurrency(entity.getCurrency());
        rule.setEffectiveFrom(entity.getEffectiveFrom());
        rule.setEffectiveTo(entity.getEffectiveTo());
        rule.setStatus(entity.getStatus());
        rule.setRemark(entity.getRemark());
        return rule;
    }
}
