package com.agenthub.tool.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.tool.domain.model.ItemQuery;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.infra.persistence.entity.ToolItemEntity;
import com.agenthub.tool.infra.persistence.mapper.ToolItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工具项仓储实现。所属域：tool。关键词里的 % 和 _ 不当作通配符。
 */
@Repository
public class ToolItemRepositoryImpl implements ToolItemRepository {

    private final ToolItemMapper mapper;

    /**
     * @param mapper 工具项表
     */
    public ToolItemRepositoryImpl(ToolItemMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * sourceIds 为空列表时直接空页，避免 IN () 打到 MySQL。
     */
    @Override
    public PageResult<ToolItem> page(ItemQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        if (query.getSourceIds() != null && query.getSourceIds().isEmpty()) {
            return PageResult.of(0, current, size, List.of());
        }
        LambdaQueryWrapper<ToolItemEntity> wrapper = new LambdaQueryWrapper<ToolItemEntity>()
                .orderByDesc(ToolItemEntity::getId);
        if (query.getSourceId() != null) {
            wrapper.eq(ToolItemEntity::getSourceId, query.getSourceId());
        }
        if (query.getSourceIds() != null) {
            wrapper.in(ToolItemEntity::getSourceId, query.getSourceIds());
        }
        if (query.getStatus() != null) {
            wrapper.eq(ToolItemEntity::getStatus, query.getStatus());
        }
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(ToolItemEntity::getName, keyword)
                    .or()
                    .like(ToolItemEntity::getTitle, keyword));
        }
        IPage<ToolItemEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolItem> findById(Long id) {
        ToolItemEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolItem> findBySourceAndName(Long sourceId, String name) {
        List<ToolItemEntity> rows = mapper.selectList(new LambdaQueryWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getSourceId, sourceId)
                .eq(ToolItemEntity::getName, name)
                .orderByAsc(ToolItemEntity::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(toDomain(rows.get(0)));
    }

    /** {@inheritDoc} */
    @Override
    public List<ToolItem> listBySourceId(Long sourceId) {
        return mapper.selectList(new LambdaQueryWrapper<ToolItemEntity>()
                        .eq(ToolItemEntity::getSourceId, sourceId)
                        .orderByAsc(ToolItemEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(ToolItem item, Long operatorId) {
        ToolItemEntity entity = new ToolItemEntity();
        entity.setSourceId(item.getSourceId());
        entity.setName(item.getName());
        entity.setTitle(item.getTitle());
        entity.setDescription(item.getDescription());
        entity.setInputSchemaJson(item.getInputSchemaJson());
        entity.setStatus(item.getStatus() == null ? 0 : item.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * 条件写在 SQL 里，和「先查再改」相比不会把并发刚写入的 schema 盖掉。
     */
    @Override
    public boolean fillInputSchemaIfBlank(Long id, String schemaJson) {
        if (id == null || schemaJson == null || schemaJson.isBlank()) {
            return false;
        }
        int rows = mapper.update(null, new LambdaUpdateWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getId, id)
                .apply("input_schema_json IS NULL OR TRIM(input_schema_json) = ''")
                .set(ToolItemEntity::getInputSchemaJson, schemaJson));
        return rows > 0;
    }

    /**
     * {@inheritDoc}
     * 故意不更新 status，同步不能把已启用的工具重新打开或关掉。
     */
    @Override
    public void updateMetadata(ToolItem item, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getId, item.getId())
                .set(ToolItemEntity::getTitle, item.getTitle())
                .set(ToolItemEntity::getDescription, item.getDescription())
                .set(ToolItemEntity::getInputSchemaJson, item.getInputSchemaJson())
                .set(ToolItemEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(Long id, int status, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getId, id)
                .set(ToolItemEntity::getStatus, status)
                .set(ToolItemEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 按源批量改状态；逻辑删除行由 @TableLogic 自动排除，不会复活已下架项。
     */
    @Override
    public int updateStatusBySourceId(Long sourceId, int status, Long operatorId) {
        return mapper.update(null, new LambdaUpdateWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getSourceId, sourceId)
                .set(ToolItemEntity::getStatus, status)
                .set(ToolItemEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getId, id)
                .set(ToolItemEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDeleteBySourceId(Long sourceId, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolItemEntity>()
                .eq(ToolItemEntity::getSourceId, sourceId)
                .set(ToolItemEntity::getUpdatedBy, operatorId));
        mapper.delete(new LambdaQueryWrapper<ToolItemEntity>().eq(ToolItemEntity::getSourceId, sourceId));
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

    private ToolItem toDomain(ToolItemEntity entity) {
        ToolItem item = new ToolItem();
        item.setId(entity.getId());
        item.setSourceId(entity.getSourceId());
        item.setName(entity.getName());
        item.setTitle(entity.getTitle());
        item.setDescription(entity.getDescription());
        item.setInputSchemaJson(entity.getInputSchemaJson());
        item.setStatus(entity.getStatus());
        item.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        item.setCreateTime(entity.getCreateTime());
        item.setUpdatedBy(entity.getUpdatedBy());
        item.setUpdateTime(entity.getUpdateTime());
        return item;
    }
}
