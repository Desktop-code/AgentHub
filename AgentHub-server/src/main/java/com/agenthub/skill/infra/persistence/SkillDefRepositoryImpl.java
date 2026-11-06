package com.agenthub.skill.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.text.LikeText;
import com.agenthub.skill.domain.enums.SkillPublishStatus;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillQuery;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.skill.infra.persistence.entity.SkillDefEntity;
import com.agenthub.skill.infra.persistence.entity.SkillVersionEntity;
import com.agenthub.skill.infra.persistence.mapper.SkillDefMapper;
import com.agenthub.skill.infra.persistence.mapper.SkillVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Skill 仓储实现。所属域：skill。Entity 不出本包。
 *
 * <p>真版本口径：{@code skill_def.content_md} 是迁移遗留列，新代码不再读写；
 * 域模型里的 contentMd 一律取自 skill_version 的当前生效已发布行；
 * activeVersionNo / hasDraft 也在本层一次查好，避免应用层拼装 SQL。
 */
@Repository
public class SkillDefRepositoryImpl implements SkillDefRepository {

    private final SkillDefMapper mapper;
    private final SkillVersionMapper versionMapper;

    /**
     * @param mapper        skill_def
     * @param versionMapper skill_version（只读生效正文与版本摘要）
     */
    public SkillDefRepositoryImpl(SkillDefMapper mapper, SkillVersionMapper versionMapper) {
        this.mapper = mapper;
        this.versionMapper = versionMapper;
    }

    /**
     * {@inheritDoc}
     * select 显式跳过 content_md，卡片页不读大字段；版本摘要用一次 IN 查询补齐。
     */
    @Override
    public PageResult<SkillDef> page(SkillQuery query) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        LambdaQueryWrapper<SkillDefEntity> wrapper = profileSelect()
                .orderByDesc(SkillDefEntity::getId);
        if (query.getStatus() != null) {
            wrapper.eq(SkillDefEntity::getStatus, query.getStatus());
        }
        String keyword = LikeText.contains(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(SkillDefEntity::getName, keyword)
                    .or()
                    .like(SkillDefEntity::getTitle, keyword));
        }
        IPage<SkillDefEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        List<SkillDef> rows = page.getRecords().stream().map(this::toDomain).toList();
        fillSummaries(rows);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * {@inheritDoc}
     * 正文与版本摘要都来自 skill_version：contentMd 取生效已发布版本，绝不读遗留列。
     */
    @Override
    public Optional<SkillDef> findById(Long id) {
        // 列表/详情都不读遗留 content_md：正文由 skill_version 的生效行提供，
        // 这里显式选列，避免每次注入装配都把整篇 MEDIUMTEXT 拉回来
        SkillDefEntity entity = mapper.selectOne(profileSelect().eq(SkillDefEntity::getId, id));
        if (entity == null) {
            return Optional.empty();
        }
        SkillDef skill = toDomain(entity);
        activeVersion(skill);
        return Optional.of(skill);
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

    /**
     * {@inheritDoc}
     * 正文与简介不落 skill_def：正文进 v1 草稿（由应用服务调用版本仓储写入），
     * 这里只写定义层字段；content_md 显式置 null，避免旧列残留被误读。
     */
    @Override
    public Long insert(SkillDef skill, Long operatorId) {
        SkillDefEntity entity = new SkillDefEntity();
        entity.setName(skill.getName());
        entity.setTitle(skill.getTitle());
        entity.setDescription(skill.getDescription());
        entity.setTriggerKeywords(skill.getTriggerKeywords());
        entity.setContentMd(null);
        entity.setVersion(skill.getVersion() == null ? "" : skill.getVersion());
        entity.setStatus(skill.getStatus());
        entity.setRemark(skill.getRemark());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * 只写定义层字段；不再写 description / content_md / version，
     * 防止「改详情就把版本号或线上简介改掉」。
     */
    @Override
    public void updateProfile(SkillDef skill, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getId, skill.getId())
                .set(SkillDefEntity::getName, skill.getName())
                .set(SkillDefEntity::getTitle, skill.getTitle())
                .set(SkillDefEntity::getTriggerKeywords, skill.getTriggerKeywords())
                .set(SkillDefEntity::getRemark, skill.getRemark())
                .set(SkillDefEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateActiveMirror(Long id, String version, String description, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getId, id)
                .set(SkillDefEntity::getVersion, version)
                .set(SkillDefEntity::getDescription, description)
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
     * 版本行不物理删除：Skill 一旦逻辑删就不可达，保留历史便于排查。
     */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillDefEntity>()
                .eq(SkillDefEntity::getId, id)
                .set(SkillDefEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /**
     * 定义层公共选列：显式跳过遗留 content_md，列表与详情共用。
     *
     * @return 已带 select 的 wrapper
     */
    private LambdaQueryWrapper<SkillDefEntity> profileSelect() {
        return new LambdaQueryWrapper<SkillDefEntity>()
                .select(SkillDefEntity::getId, SkillDefEntity::getName, SkillDefEntity::getTitle,
                        SkillDefEntity::getDescription, SkillDefEntity::getTriggerKeywords,
                        SkillDefEntity::getVersion, SkillDefEntity::getStatus,
                        SkillDefEntity::getRemark, SkillDefEntity::getCreatedBy, SkillDefEntity::getCreateTime,
                        SkillDefEntity::getUpdatedBy, SkillDefEntity::getUpdateTime);
    }


    /**
     * 单条补齐生效版正文与版本摘要。
     *
     * @param skill 已填充定义层字段的领域对象
     */
    private void activeVersion(SkillDef skill) {
        if (skill.getId() == null) {
            return;
        }
        SkillVersionEntity active = selectActiveVersion(skill.getId());
        if (active == null) {
            skill.setActiveVersionNo(null);
            skill.setHasDraft(hasDraft(skill.getId()));
            return;
        }
        skill.setContentMd(active.getContentMd());
        skill.setActiveVersionNo(active.getVersionNo());
        skill.setHasDraft(hasDraft(skill.getId()));
    }

    /**
     * 批量补版本摘要（列表页）。
     *
     * @param rows 已映射的领域对象
     */
    private void fillSummaries(List<SkillDef> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(SkillDef::getId).toList();
        List<SkillVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<SkillVersionEntity>()
                .select(SkillVersionEntity::getSkillId, SkillVersionEntity::getVersionNo,
                        SkillVersionEntity::getPublishStatus, SkillVersionEntity::getIsActive)
                .in(SkillVersionEntity::getSkillId, ids));
        Map<Long, Integer> activeNos = new HashMap<>();
        Set<Long> drafts = new HashSet<>();
        for (SkillVersionEntity row : versions) {
            if (row.getSkillId() == null) {
                continue;
            }
            if (SkillPublishStatus.isDraft(row.getPublishStatus())) {
                drafts.add(row.getSkillId());
            } else if (row.getIsActive() != null && row.getIsActive() == 1) {
                activeNos.put(row.getSkillId(), row.getVersionNo());
            }
        }
        for (SkillDef row : rows) {
            row.setActiveVersionNo(activeNos.get(row.getId()));
            row.setHasDraft(drafts.contains(row.getId()));
        }
    }

    /**
     * @param skillId Skill 主键
     * @return 生效已发布版本行，含正文；没有则 null
     */
    private SkillVersionEntity selectActiveVersion(Long skillId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getSkillId, skillId)
                .eq(SkillVersionEntity::getIsActive, 1)
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.PUBLISHED.getCode())
                .last("LIMIT 1"));
    }

    /**
     * @param skillId Skill 主键
     * @return true 表示存在草稿
     */
    private boolean hasDraft(Long skillId) {
        return versionMapper.selectCount(new LambdaQueryWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getSkillId, skillId)
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.DRAFT.getCode())) > 0;
    }

    private SkillDef toDomain(SkillDefEntity entity) {
        SkillDef skill = new SkillDef();
        skill.setId(entity.getId());
        skill.setName(entity.getName());
        skill.setTitle(entity.getTitle());
        skill.setDescription(entity.getDescription());
        skill.setTriggerKeywords(entity.getTriggerKeywords());
        // 遗留列不读：正文由 activeVersion 从 skill_version 填充
        skill.setContentMd(null);
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
