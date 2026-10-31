package com.agenthub.prompt.infra.persistence;

import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptConstraint;
import com.agenthub.prompt.domain.repository.PromptConstraintRepository;
import com.agenthub.prompt.infra.persistence.entity.PromptConstraintEntity;
import com.agenthub.prompt.infra.persistence.mapper.PromptConstraintMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * L1 仓储实现。所属域：prompt。Entity 不出本包。
 */
@Repository
public class PromptConstraintRepositoryImpl implements PromptConstraintRepository {

    private final PromptConstraintMapper mapper;

    /**
     * @param mapper prompt_global_constraint
     */
    public PromptConstraintRepositoryImpl(PromptConstraintMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<PromptConstraint> listByLocale(String locale) {
        LambdaQueryWrapper<PromptConstraintEntity> wrapper = new LambdaQueryWrapper<PromptConstraintEntity>()
                .orderByDesc(PromptConstraintEntity::getVersion)
                .orderByDesc(PromptConstraintEntity::getId);
        if (locale != null && !locale.isBlank()) {
            wrapper.eq(PromptConstraintEntity::getLocale, locale.trim());
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptConstraint> findById(Long id) {
        PromptConstraintEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public int maxVersion(String locale) {
        LambdaQueryWrapper<PromptConstraintEntity> wrapper = new LambdaQueryWrapper<PromptConstraintEntity>()
                .eq(PromptConstraintEntity::getLocale, locale)
                .orderByDesc(PromptConstraintEntity::getVersion)
                .last("LIMIT 1");
        PromptConstraintEntity top = mapper.selectOne(wrapper);
        return top == null || top.getVersion() == null ? 0 : top.getVersion();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(PromptConstraint constraint, Long operatorId) {
        PromptConstraintEntity entity = new PromptConstraintEntity();
        entity.setLocale(constraint.getLocale());
        entity.setContent(constraint.getContent());
        entity.setVersion(constraint.getVersion());
        entity.setStatus(constraint.getStatus());
        entity.setIsActive(constraint.getIsActive());
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateDraftContent(PromptConstraint constraint, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptConstraintEntity>()
                .eq(PromptConstraintEntity::getId, constraint.getId())
                .eq(PromptConstraintEntity::getStatus, PromptPublishStatus.DRAFT.getCode())
                .set(PromptConstraintEntity::getContent, constraint.getContent())
                .set(PromptConstraintEntity::getUpdateBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void clearActive(String locale, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptConstraintEntity>()
                .eq(PromptConstraintEntity::getLocale, locale)
                .eq(PromptConstraintEntity::getIsActive, 1)
                .set(PromptConstraintEntity::getIsActive, 0)
                .set(PromptConstraintEntity::getUpdateBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void markPublishedActive(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptConstraintEntity>()
                .eq(PromptConstraintEntity::getId, id)
                .set(PromptConstraintEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                .set(PromptConstraintEntity::getIsActive, 1)
                .set(PromptConstraintEntity::getUpdateBy, operatorId));
    }

    private PromptConstraint toDomain(PromptConstraintEntity entity) {
        PromptConstraint domain = new PromptConstraint();
        domain.setId(entity.getId());
        domain.setLocale(entity.getLocale());
        domain.setContent(entity.getContent());
        domain.setVersion(entity.getVersion());
        domain.setStatus(entity.getStatus());
        domain.setIsActive(entity.getIsActive());
        domain.setCreateBy(entity.getCreateBy());
        domain.setUpdateBy(entity.getUpdateBy());
        domain.setCreateTime(entity.getCreateTime());
        domain.setUpdateTime(entity.getUpdateTime());
        return domain;
    }
}
