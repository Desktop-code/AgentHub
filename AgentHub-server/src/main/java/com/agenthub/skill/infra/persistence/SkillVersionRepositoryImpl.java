package com.agenthub.skill.infra.persistence;

import com.agenthub.skill.domain.enums.SkillPublishStatus;
import com.agenthub.skill.domain.model.SkillVersion;
import com.agenthub.skill.domain.model.SkillVersionSummary;
import com.agenthub.skill.domain.repository.SkillVersionRepository;
import com.agenthub.skill.infra.persistence.entity.SkillVersionEntity;
import com.agenthub.skill.infra.persistence.mapper.SkillVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Skill 版本仓储实现。所属域：skill。Entity 不出本包。
 *
 * <p>唯一生效用「先清后置」两步完成；DB 生成列唯一索引是最后一道闸，
 * 所以即使并发发布也只可能有一行 is_active=1。
 */
@Repository
public class SkillVersionRepositoryImpl implements SkillVersionRepository {

    private final SkillVersionMapper mapper;

    /**
     * @param mapper skill_version
     */
    public SkillVersionRepositoryImpl(SkillVersionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * 显式跳过 content_md：版本列表不读大字段。
     */
    @Override
    public List<SkillVersion> listBySkillId(Long skillId) {
        List<SkillVersionEntity> rows = mapper.selectList(new LambdaQueryWrapper<SkillVersionEntity>()
                .select(SkillVersionEntity::getId, SkillVersionEntity::getSkillId, SkillVersionEntity::getVersionNo,
                        SkillVersionEntity::getDescription, SkillVersionEntity::getPublishStatus,
                        SkillVersionEntity::getIsActive, SkillVersionEntity::getPublishedAt,
                        SkillVersionEntity::getPublishedBy, SkillVersionEntity::getCreateTime,
                        SkillVersionEntity::getUpdateTime)
                .eq(SkillVersionEntity::getSkillId, skillId)
                .orderByDesc(SkillVersionEntity::getVersionNo));
        return rows.stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillVersion> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        SkillVersionEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillVersion> findActive(Long skillId) {
        if (skillId == null) {
            return Optional.empty();
        }
        SkillVersionEntity entity = mapper.selectOne(new LambdaQueryWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getSkillId, skillId)
                .eq(SkillVersionEntity::getIsActive, 1)
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.PUBLISHED.getCode())
                // 生成列唯一索引已保证至多一行，这里再限一次防御历史脏数据
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillVersion> findDraft(Long skillId) {
        if (skillId == null) {
            return Optional.empty();
        }
        SkillVersionEntity entity = mapper.selectOne(new LambdaQueryWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getSkillId, skillId)
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.DRAFT.getCode())
                .orderByDesc(SkillVersionEntity::getVersionNo)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public int nextVersionNo(Long skillId) {
        SkillVersionEntity top = mapper.selectOne(new LambdaQueryWrapper<SkillVersionEntity>()
                .select(SkillVersionEntity::getVersionNo)
                .eq(SkillVersionEntity::getSkillId, skillId)
                .orderByDesc(SkillVersionEntity::getVersionNo)
                .last("LIMIT 1"));
        return top == null || top.getVersionNo() == null ? 1 : top.getVersionNo() + 1;
    }

    /**
     * {@inheritDoc}
     * create_time / update_time 由库默认值填充；publish_status 与 is_active 显式落 0，避免依赖库默认。
     */
    @Override
    public Long insert(SkillVersion version) {
        SkillVersionEntity entity = new SkillVersionEntity();
        entity.setSkillId(version.getSkillId());
        entity.setVersionNo(version.getVersionNo());
        entity.setContentMd(version.getContentMd());
        entity.setDescription(version.getDescription());
        entity.setPublishStatus(SkillPublishStatus.DRAFT.getCode());
        entity.setIsActive(0);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * update 条件带 publish_status=0：已发布行即使被误传 id 也不会改正文。
     */
    @Override
    public boolean updateDraft(SkillVersion version) {
        int rows = mapper.update(null, new LambdaUpdateWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getId, version.getId())
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.DRAFT.getCode())
                .set(SkillVersionEntity::getDescription, version.getDescription())
                .set(SkillVersionEntity::getContentMd, version.getContentMd()));
        return rows > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void clearActive(Long skillId) {
        mapper.update(null, new LambdaUpdateWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getSkillId, skillId)
                .eq(SkillVersionEntity::getIsActive, 1)
                .set(SkillVersionEntity::getIsActive, 0));
    }

    /**
     * {@inheritDoc}
     * 条件带 publish_status=0，只有草稿才能被发布；重复发布同一 id 影响 0 行。
     */
    @Override
    public boolean markPublished(Long id, Long operatorId, LocalDateTime publishedAt) {
        int rows = mapper.update(null, new LambdaUpdateWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getId, id)
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.DRAFT.getCode())
                .set(SkillVersionEntity::getPublishStatus, SkillPublishStatus.PUBLISHED.getCode())
                .set(SkillVersionEntity::getPublishedBy, operatorId)
                .set(SkillVersionEntity::getPublishedAt, publishedAt));
        return rows > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void activate(Long id) {
        mapper.update(null, new LambdaUpdateWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getId, id)
                .set(SkillVersionEntity::getIsActive, 1));
    }

    /**
     * {@inheritDoc}
     * delete 条件带 publish_status=0：只有草稿能被物理删除，已发布历史行不动。
     */
    @Override
    public boolean deleteDraft(Long id) {
        int rows = mapper.delete(new LambdaQueryWrapper<SkillVersionEntity>()
                .eq(SkillVersionEntity::getId, id)
                .eq(SkillVersionEntity::getPublishStatus, SkillPublishStatus.DRAFT.getCode()));
        return rows > 0;
    }

    /**
     * {@inheritDoc}
     * 一次 IN 查询合并两种标记：已发布且生效的给 activeVersionNo，草稿置 hasDraft。
     */
    @Override
    public Map<Long, SkillVersionSummary> summariesBySkillIds(Collection<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        // 去重后再查，调用方可能把同一 id 传多次
        Set<Long> ids = Set.copyOf(skillIds);
        List<SkillVersionEntity> rows = mapper.selectList(new LambdaQueryWrapper<SkillVersionEntity>()
                .select(SkillVersionEntity::getSkillId, SkillVersionEntity::getVersionNo,
                        SkillVersionEntity::getPublishStatus, SkillVersionEntity::getIsActive)
                .in(SkillVersionEntity::getSkillId, ids));
        Map<Long, Integer> activeNos = new HashMap<>();
        Set<Long> drafts = new HashSet<>();
        for (SkillVersionEntity row : rows) {
            if (row.getSkillId() == null) {
                continue;
            }
            if (SkillPublishStatus.isDraft(row.getPublishStatus())) {
                drafts.add(row.getSkillId());
            } else if (row.getIsActive() != null && row.getIsActive() == 1) {
                activeNos.put(row.getSkillId(), row.getVersionNo());
            }
        }
        Map<Long, SkillVersionSummary> out = new HashMap<>();
        for (Long id : ids) {
            SkillVersionSummary summary = SkillVersionSummary.of(activeNos.get(id), drafts.contains(id));
            // 既无生效版也无草稿的 Skill 不放进 map，调用方按 EMPTY 兜底
            if (summary.activeVersionNo() != null || summary.hasDraft()) {
                out.put(id, summary);
            }
        }
        return out;
    }

    /**
     * Entity → 领域模型。列表查询不带正文，此时 contentMd 为 null。
     *
     * @param entity 库行
     * @return 领域对象
     */
    private SkillVersion toDomain(SkillVersionEntity entity) {
        SkillVersion version = new SkillVersion();
        version.setId(entity.getId());
        version.setSkillId(entity.getSkillId());
        version.setVersionNo(entity.getVersionNo());
        version.setContentMd(entity.getContentMd());
        version.setDescription(entity.getDescription());
        version.setPublishStatus(entity.getPublishStatus());
        version.setIsActive(entity.getIsActive());
        version.setPublishedAt(entity.getPublishedAt());
        version.setPublishedBy(entity.getPublishedBy());
        version.setCreateTime(entity.getCreateTime());
        version.setUpdateTime(entity.getUpdateTime());
        return version;
    }
}
