package com.agenthub.skill.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillQuery;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.skill.infra.persistence.entity.SkillDefEntity;
import com.agenthub.skill.infra.persistence.mapper.SkillDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Skill 仓储实现。所属域：skill。Entity 不出本包。
 */
@Repository
public class SkillDefRepositoryImpl implements SkillDefRepository {

    private final SkillDefMapper mapper;

    /**
     * @param mapper skill_def
     */
    public SkillDefRepositoryImpl(SkillDefMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * select 显式跳过 content_md，卡片页不读大字段。
     */
    @Override
    public PageResult<SkillDef> page(SkillQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        LambdaQueryWrapper<SkillDefEntity> wrapper = new LambdaQueryWrapper<SkillDefEntity>()
                .select(SkillDefEntity::getId, SkillDefEntity::getName, SkillDefEntity::getTitle,
                        SkillDefEntity::getDescription, SkillDefEntity::getTriggerKeywords,
                        SkillDefEntity::getVersion, SkillDefEntity::getStatus,
                        SkillDefEntity::getRemark, SkillDefEntity::getCreatedBy, SkillDefEntity::getCreateTime,
                        SkillDefEntity::getUpdatedBy, SkillDefEntity::getUpdateTime)
                .orderByDesc(SkillDefEntity::getId);
        if (query.getStatus() != null) {
            wrapper.eq(SkillDefEntity::getStatus, query.getStatus());
        }
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(SkillDefEntity::getName, keyword)
                    .or()
                    .like(SkillDefEntity::getTitle, keyword));
        }
        IPage<SkillDefEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillDef> findById(Long id) {
        SkillDefEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByName(String name, Long excludeId) {
        LambdaQueryWrapper<SkillDefEntity> wrapper = new LambdaQueryWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(SkillDefEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(SkillDef skill, Long operatorId) {
        SkillDefEntity entity = new SkillDefEntity();
        entity.setName(skill.getName());
        entity.setTitle(skill.getTitle());
        entity.setDescription(skill.getDescription());
        entity.setTriggerKeywords(skill.getTriggerKeywords());
        entity.setContentMd(skill.getContentMd());
        entity.setVersion(skill.getVersion());
        entity.setStatus(skill.getStatus());
        entity.setRemark(skill.getRemark());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(SkillDef skill, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getId, skill.getId())
                .set(SkillDefEntity::getName, skill.getName())
                .set(SkillDefEntity::getTitle, skill.getTitle())
                .set(SkillDefEntity::getDescription, skill.getDescription())
                .set(SkillDefEntity::getTriggerKeywords, skill.getTriggerKeywords())
                .set(SkillDefEntity::getContentMd, skill.getContentMd())
                .set(SkillDefEntity::getVersion, skill.getVersion())
                .set(SkillDefEntity::getRemark, skill.getRemark())
                .set(SkillDefEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(Long id, int status, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getId, id)
                .set(SkillDefEntity::getStatus, status)
                .set(SkillDefEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 先记下操作人，再走逻辑删除，避免只改 deleted 时丢更新人。
     */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getId, id)
                .set(SkillDefEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /**
     * MySQL 默认用反斜杠转义 LIKE。去掉用户输入里的通配符含义。
     */
    private String likeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private SkillDef toDomain(SkillDefEntity entity) {
        SkillDef skill = new SkillDef();
        skill.setId(entity.getId());
        skill.setName(entity.getName());
        skill.setTitle(entity.getTitle());
        skill.setDescription(entity.getDescription());
        skill.setTriggerKeywords(entity.getTriggerKeywords());
        skill.setContentMd(entity.getContentMd());
        skill.setVersion(entity.getVersion());
        skill.setStatus(entity.getStatus());
        skill.setRemark(entity.getRemark());
        skill.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        skill.setCreateTime(entity.getCreateTime());
        skill.setUpdatedBy(entity.getUpdatedBy());
        skill.setUpdateTime(entity.getUpdateTime());
        return skill;
    }
}
