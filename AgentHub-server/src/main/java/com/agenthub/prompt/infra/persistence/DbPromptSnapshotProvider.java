package com.agenthub.prompt.infra.persistence;

import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.infra.persistence.entity.PromptConstraintEntity;
import com.agenthub.prompt.infra.persistence.entity.PromptSegmentEntity;
import com.agenthub.prompt.infra.persistence.entity.PromptTemplateEntity;
import com.agenthub.prompt.infra.persistence.mapper.PromptConstraintMapper;
import com.agenthub.prompt.infra.persistence.mapper.PromptSegmentMapper;
import com.agenthub.prompt.infra.persistence.mapper.PromptTemplateMapper;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 运行时快照的 DB 直读实现。所属域：prompt 的 infra。
 * <p>
 * 职责：只读**已发布**行，把 Entity 转成可缓存的快照；不含缓存与拼装。
 * 调用方是缓存装饰器 {@code CachedPromptSnapshotProvider}，正常情况下很少直接打到本类。
 * <p>
 * 约束：
 * 1. 一律带 {@code status=已发布}；草稿永不出现在运行时路径，避免「保存即生效」。
 * 2. 只读，无事务、无写；不得在此做业务判断（purpose 一致性由 Assembler 判）。
 * 3. 段落一次性批量取，按 sort_no 排序，避免 N+1。
 */
@Repository("dbPromptSnapshotProvider")
public class DbPromptSnapshotProvider implements PromptSnapshotProvider {

    private final PromptConstraintMapper constraints;
    private final PromptTemplateMapper templates;
    private final PromptSegmentMapper segments;

    /**
     * @param constraints prompt_global_constraint
     * @param templates   prompt_template
     * @param segments    prompt_template_segment
     */
    public DbPromptSnapshotProvider(PromptConstraintMapper constraints, PromptTemplateMapper templates,
                                    PromptSegmentMapper segments) {
        this.constraints = constraints;
        this.templates = templates;
        this.segments = segments;
    }

    /**
     * {@inheritDoc}
     * 同 locale 可能因脏数据出现多条 is_active=1，取 version 最大那条，行为可预期。
     */
    @Override
    public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
        PromptConstraintEntity entity = constraints.selectOne(new LambdaQueryWrapper<PromptConstraintEntity>()
                .eq(PromptConstraintEntity::getLocale, locale)
                .eq(PromptConstraintEntity::getIsActive, 1)
                .eq(PromptConstraintEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                .orderByDesc(PromptConstraintEntity::getVersion)
                .last("LIMIT 1"));
        if (entity == null || entity.getContent() == null || entity.getContent().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PromptConstraintSnapshot(entity.getLocale(), entity.getVersion(), entity.getContent()));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version) {
        PromptTemplateEntity entity = templates.selectOne(new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getTemplateKey, templateKey)
                .eq(PromptTemplateEntity::getLocale, locale)
                .eq(PromptTemplateEntity::getVersion, version)
                .eq(PromptTemplateEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toSnapshot(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateSnapshot> templateById(Long id) {
        PromptTemplateEntity entity = templates.selectById(id);
        if (entity == null || entity.getStatus() == null
                || entity.getStatus() != PromptPublishStatus.PUBLISHED.getCode()) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale) {
        return toRef(templates.selectOne(new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getPurpose, purpose)
                .eq(PromptTemplateEntity::getLocale, locale)
                .eq(PromptTemplateEntity::getIsActive, 1)
                .eq(PromptTemplateEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                .orderByDesc(PromptTemplateEntity::getVersion)
                .last("LIMIT 1")));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale) {
        return toRef(templates.selectOne(new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getTemplateKey, templateKey)
                .eq(PromptTemplateEntity::getLocale, locale)
                .eq(PromptTemplateEntity::getIsActive, 1)
                .eq(PromptTemplateEntity::getStatus, PromptPublishStatus.PUBLISHED.getCode())
                .orderByDesc(PromptTemplateEntity::getVersion)
                .last("LIMIT 1")));
    }

    /**
     * 主表 + 段落 → 快照。段落按 sort_no、id 升序，组装顺序即此处顺序。
     */
    private PromptTemplateSnapshot toSnapshot(PromptTemplateEntity entity) {
        List<PromptSegmentSnapshot> segs = new ArrayList<>();
        List<PromptSegmentEntity> rows = segments.selectList(new LambdaQueryWrapper<PromptSegmentEntity>()
                .eq(PromptSegmentEntity::getTemplateId, entity.getId())
                .orderByAsc(PromptSegmentEntity::getSortNo)
                .orderByAsc(PromptSegmentEntity::getId));
        for (PromptSegmentEntity row : rows) {
            segs.add(new PromptSegmentSnapshot(row.getSegKey(), row.getDisplayName(), row.getSortNo(),
                    row.getMutable(), row.getRequired(), row.getContent()));
        }
        segs.sort(Comparator.comparingInt(item -> item.getSortNo() == null ? 0 : item.getSortNo()));
        return new PromptTemplateSnapshot(entity.getId(), entity.getTemplateKey(), entity.getPurpose(),
                entity.getLocale(), entity.getProtocol(), entity.getVersion(), segs);
    }

    private Optional<PromptTemplateRef> toRef(PromptTemplateEntity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new PromptTemplateRef(entity.getId(), entity.getTemplateKey(), entity.getPurpose(),
                entity.getLocale(), entity.getVersion()));
    }
}
