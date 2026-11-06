package com.agenthub.prompt.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.model.PromptTemplateQuery;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import com.agenthub.prompt.infra.persistence.entity.PromptTemplateEntity;
import com.agenthub.prompt.infra.persistence.mapper.PromptTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * L2 主表仓储实现。所属域：prompt。Entity 不出本包。
 */
@Repository
public class PromptTemplateRepositoryImpl implements PromptTemplateRepository {

    private final PromptTemplateMapper mapper;

    /**
     * @param mapper prompt_template
     */
    public PromptTemplateRepositoryImpl(PromptTemplateMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<PromptTemplate> page(PromptTemplateQuery query) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        LambdaQueryWrapper<PromptTemplateEntity> wrapper = new LambdaQueryWrapper<PromptTemplateEntity>()
                .orderByDesc(PromptTemplateEntity::getId);
        if (query.getPurpose() != null && !query.getPurpose().isBlank()) {
            wrapper.eq(PromptTemplateEntity::getPurpose, query.getPurpose().trim());
        }
        // 精确键：中栏按对象取版本，避免 keyword 模糊把同前缀模板带进来
        if (query.getTemplateKey() != null && !query.getTemplateKey().isBlank()) {
            wrapper.eq(PromptTemplateEntity::getTemplateKey, query.getTemplateKey().trim());
        }
        if (query.getLocale() != null && !query.getLocale().isBlank()) {
            wrapper.eq(PromptTemplateEntity::getLocale, query.getLocale().trim());
        }
        if (query.getStatus() != null) {
            wrapper.eq(PromptTemplateEntity::getStatus, query.getStatus());
        }
        if (query.getIsActive() != null) {
            wrapper.eq(PromptTemplateEntity::getIsActive, query.getIsActive());
        }
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            // 关键词同时扫业务键与中文名，方便运营检索
            wrapper.and(one -> one.like(PromptTemplateEntity::getTemplateKey, keyword)
                    .or()
                    .like(PromptTemplateEntity::getName, keyword));
        }
        IPage<PromptTemplateEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public List<PromptTemplate> listByLocale(String locale) {
        LambdaQueryWrapper<PromptTemplateEntity> wrapper = new LambdaQueryWrapper<PromptTemplateEntity>()
                .orderByAsc(PromptTemplateEntity::getTemplateKey)
                .orderByDesc(PromptTemplateEntity::getVersion);
        if (locale != null && !locale.isBlank()) {
            wrapper.eq(PromptTemplateEntity::getLocale, locale.trim());
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<PromptTemplate> listPublishedByPurpose(String purpose, String locale) {
        // 只取已发布：草稿不可被 Agent 钉死，否则「绑上了却永远不生效」极难排查
        return mapper.selectList(new LambdaQueryWrapper<PromptTemplateEntity>()
                        .eq(PromptTemplateEntity::getPurpose, purpose)
                        .eq(PromptTemplateEntity::getLocale, locale)
                        .eq(PromptTemplateEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                        .orderByDesc(PromptTemplateEntity::getVersion))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplate> findById(Long id) {
        PromptTemplateEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public int maxVersion(String templateKey, String locale) {
        LambdaQueryWrapper<PromptTemplateEntity> wrapper = new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getTemplateKey, templateKey)
                .eq(PromptTemplateEntity::getLocale, locale)
                .orderByDesc(PromptTemplateEntity::getVersion)
                .last("LIMIT 1");
        PromptTemplateEntity top = mapper.selectOne(wrapper);
        return top == null || top.getVersion() == null ? 0 : top.getVersion();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(PromptTemplate template, Long operatorId) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        copyMain(template, entity);
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateDraft(PromptTemplate template, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getId, template.getId())
                .eq(PromptTemplateEntity::getStatus, PromptPublishStatus.DRAFT.getCode())
                .set(PromptTemplateEntity::getName, template.getName())
                .set(PromptTemplateEntity::getPurpose, template.getPurpose())
                .set(PromptTemplateEntity::getProtocol, template.getProtocol())
                .set(PromptTemplateEntity::getRemark, template.getRemark())
                .set(PromptTemplateEntity::getUpdateBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void clearActive(String templateKey, String locale, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getTemplateKey, templateKey)
                .eq(PromptTemplateEntity::getLocale, locale)
                .eq(PromptTemplateEntity::getIsActive, 1)
                .set(PromptTemplateEntity::getIsActive, 0)
                .set(PromptTemplateEntity::getUpdateBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 比 clearActive 范围更宽：同用途不同 key 也会被熄灭，保证 purpose+locale 唯一生效。
     */
    @Override
    public void clearActiveByPurpose(String purpose, String locale, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getPurpose, purpose)
                .eq(PromptTemplateEntity::getLocale, locale)
                .eq(PromptTemplateEntity::getIsActive, 1)
                .set(PromptTemplateEntity::getIsActive, 0)
                .set(PromptTemplateEntity::getUpdateBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void markPublishedActive(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getId, id)
                .set(PromptTemplateEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                .set(PromptTemplateEntity::getIsActive, 1)
                .set(PromptTemplateEntity::getUpdateBy, operatorId));
    }

    private void copyMain(PromptTemplate template, PromptTemplateEntity entity) {
        entity.setTemplateKey(template.getTemplateKey());
        entity.setName(template.getName());
        entity.setPurpose(template.getPurpose());
        entity.setLocale(template.getLocale());
        entity.setProtocol(template.getProtocol());
        entity.setVersion(template.getVersion());
        entity.setStatus(template.getStatus());
        entity.setIsActive(template.getIsActive());
        entity.setRemark(template.getRemark());
    }

    private PromptTemplate toDomain(PromptTemplateEntity entity) {
        PromptTemplate domain = new PromptTemplate();
        domain.setId(entity.getId());
        domain.setTemplateKey(entity.getTemplateKey());
        domain.setName(entity.getName());
        domain.setPurpose(entity.getPurpose());
        domain.setLocale(entity.getLocale());
        domain.setProtocol(entity.getProtocol());
        domain.setVersion(entity.getVersion());
        domain.setStatus(entity.getStatus());
        domain.setIsActive(entity.getIsActive());
        domain.setRemark(entity.getRemark());
        domain.setCreateBy(entity.getCreateBy());
        domain.setUpdateBy(entity.getUpdateBy());
        domain.setCreateTime(entity.getCreateTime());
        domain.setUpdateTime(entity.getUpdateTime());
        return domain;
    }

    private String likeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
