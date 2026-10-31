package com.agenthub.prompt.infra.persistence;

import com.agenthub.prompt.domain.model.PromptSegment;
import com.agenthub.prompt.domain.repository.PromptSegmentRepository;
import com.agenthub.prompt.infra.persistence.entity.PromptSegmentEntity;
import com.agenthub.prompt.infra.persistence.mapper.PromptSegmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * L2 段落仓储实现。所属域：prompt。Entity 不出本包。
 */
@Repository
public class PromptSegmentRepositoryImpl implements PromptSegmentRepository {

    private final PromptSegmentMapper mapper;

    /**
     * @param mapper prompt_template_segment
     */
    public PromptSegmentRepositoryImpl(PromptSegmentMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<PromptSegment> listByTemplateId(Long templateId) {
        return mapper.selectList(new LambdaQueryWrapper<PromptSegmentEntity>()
                        .eq(PromptSegmentEntity::getTemplateId, templateId)
                        .orderByAsc(PromptSegmentEntity::getSortNo)
                        .orderByAsc(PromptSegmentEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     * 物理清空再插入：uk_tpl_seg 含已软删行，软删后重插同 seg_key 会撞唯一键。
     */
    @Override
    public void replaceAll(Long templateId, List<PromptSegment> segments) {
        mapper.physicalDeleteByTemplateId(templateId);
        if (segments == null || segments.isEmpty()) {
            return;
        }
        for (PromptSegment segment : segments) {
            PromptSegmentEntity entity = new PromptSegmentEntity();
            entity.setTemplateId(templateId);
            entity.setSegKey(segment.getSegKey());
            entity.setDisplayName(segment.getDisplayName());
            entity.setSortNo(segment.getSortNo() == null ? 0 : segment.getSortNo());
            entity.setMutable(segment.getMutable() == null ? 0 : segment.getMutable());
            entity.setRequired(segment.getRequired() == null ? 1 : segment.getRequired());
            entity.setContent(segment.getContent());
            mapper.insert(entity);
        }
    }

    private PromptSegment toDomain(PromptSegmentEntity entity) {
        PromptSegment domain = new PromptSegment();
        domain.setId(entity.getId());
        domain.setTemplateId(entity.getTemplateId());
        domain.setSegKey(entity.getSegKey());
        domain.setDisplayName(entity.getDisplayName());
        domain.setSortNo(entity.getSortNo());
        domain.setMutable(entity.getMutable());
        domain.setRequired(entity.getRequired());
        domain.setContent(entity.getContent());
        domain.setCreateTime(entity.getCreateTime());
        domain.setUpdateTime(entity.getUpdateTime());
        return domain;
    }
}
