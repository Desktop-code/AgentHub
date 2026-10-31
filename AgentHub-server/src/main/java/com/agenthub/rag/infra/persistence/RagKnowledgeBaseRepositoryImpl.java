package com.agenthub.rag.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.rag.domain.model.RagKbQuery;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.infra.persistence.entity.RagKnowledgeBaseEntity;
import com.agenthub.rag.infra.persistence.mapper.RagKnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 知识库仓储实现。所属域：rag 的 infra。Entity 不出本包。
 */
@Repository
public class RagKnowledgeBaseRepositoryImpl implements RagKnowledgeBaseRepository {

    private final RagKnowledgeBaseMapper mapper;

    /**
     * @param mapper rag_knowledge_base
     */
    public RagKnowledgeBaseRepositoryImpl(RagKnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * 显式列出列，避免将来加 TEXT 大字段时整列被拉出来。
     */
    @Override
    public PageResult<RagKnowledgeBase> page(RagKbQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        LambdaQueryWrapper<RagKnowledgeBaseEntity> wrapper = new LambdaQueryWrapper<RagKnowledgeBaseEntity>()
                .orderByDesc(RagKnowledgeBaseEntity::getId);
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(RagKnowledgeBaseEntity::getStatus, query.getStatus().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.getVisibility())) {
            wrapper.eq(RagKnowledgeBaseEntity::getVisibility, query.getVisibility().trim().toUpperCase());
        }
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(RagKnowledgeBaseEntity::getName, keyword)
                    .or()
                    .like(RagKnowledgeBaseEntity::getDescription, keyword));
        }
        IPage<RagKnowledgeBaseEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RagKnowledgeBase> findById(Long id) {
        RagKnowledgeBaseEntity entity = id == null ? null : mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByName(String name, Long excludeId) {
        LambdaQueryWrapper<RagKnowledgeBaseEntity> wrapper = new LambdaQueryWrapper<RagKnowledgeBaseEntity>()
                .eq(RagKnowledgeBaseEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(RagKnowledgeBaseEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /**
     * {@inheritDoc}
     * 新建时状态先落 INIT_FAILED：建集成功后再改成 READY。
     * 这样即使进程在建集中途挂掉，也不会留下一个「看着可用其实没有集合」的知识库。
     */
    @Override
    public Long insert(RagKnowledgeBase kb, Long operatorId) {
        RagKnowledgeBaseEntity entity = new RagKnowledgeBaseEntity();
        entity.setTenantId(kb.getTenantId() == null ? 0L : kb.getTenantId());
        entity.setName(kb.getName());
        entity.setDescription(kb.getDescription());
        entity.setStoreType(kb.getStoreType());
        entity.setEmbeddingModelId(kb.getEmbeddingModelId());
        entity.setVectorDimension(kb.getVectorDimension());
        entity.setRerankModelId(kb.getRerankModelId());
        entity.setIsolationMode(kb.getIsolationMode());
        entity.setVisibility(kb.getVisibility());
        entity.setRetrievalConfig(kb.getRetrievalConfig());
        entity.setIngestConfig(kb.getIngestConfig());
        entity.setStatus(kb.getStatus());
        entity.setErrorMsg(kb.getErrorMsg());
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(RagKnowledgeBase kb, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<RagKnowledgeBaseEntity>()
                .eq(RagKnowledgeBaseEntity::getId, kb.getId())
                .set(RagKnowledgeBaseEntity::getName, kb.getName())
                .set(RagKnowledgeBaseEntity::getDescription, kb.getDescription())
                .set(RagKnowledgeBaseEntity::getVisibility, kb.getVisibility())
                .set(RagKnowledgeBaseEntity::getRerankModelId, kb.getRerankModelId())
                .set(RagKnowledgeBaseEntity::getRetrievalConfig, kb.getRetrievalConfig())
                .set(RagKnowledgeBaseEntity::getIngestConfig, kb.getIngestConfig())
                .set(RagKnowledgeBaseEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 用 set(..., null) 而非 setSql 才能把 error_msg 真正写空。
     */
    @Override
    public void updateInitResult(Long id, String collectionName, String capability,
                                 String status, String errorMsg, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<RagKnowledgeBaseEntity>()
                .eq(RagKnowledgeBaseEntity::getId, id)
                .set(RagKnowledgeBaseEntity::getCollectionName, collectionName)
                .set(RagKnowledgeBaseEntity::getStoreCapability, capability)
                .set(RagKnowledgeBaseEntity::getStatus, status)
                .set(RagKnowledgeBaseEntity::getErrorMsg, errorMsg)
                .set(RagKnowledgeBaseEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 先记更新人再逻辑删除，避免只剩 deleted=1 而丢失操作痕迹。
     */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<RagKnowledgeBaseEntity>()
                .eq(RagKnowledgeBaseEntity::getId, id)
                .set(RagKnowledgeBaseEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /**
     * 转义 LIKE 通配符：用户输入里的 % / _ 按字面匹配。
     */
    private String likeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private RagKnowledgeBase toDomain(RagKnowledgeBaseEntity entity) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(entity.getId());
        kb.setTenantId(entity.getTenantId());
        kb.setName(entity.getName());
        kb.setDescription(entity.getDescription());
        kb.setStoreType(entity.getStoreType());
        kb.setCollectionName(entity.getCollectionName());
        kb.setStoreCapability(entity.getStoreCapability());
        kb.setEmbeddingModelId(entity.getEmbeddingModelId());
        kb.setVectorDimension(entity.getVectorDimension());
        kb.setRerankModelId(entity.getRerankModelId());
        kb.setIsolationMode(entity.getIsolationMode());
        kb.setVisibility(entity.getVisibility());
        kb.setRetrievalConfig(entity.getRetrievalConfig());
        kb.setIngestConfig(entity.getIngestConfig());
        kb.setStatus(entity.getStatus());
        kb.setErrorMsg(entity.getErrorMsg());
        kb.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        kb.setCreateTime(entity.getCreateTime());
        kb.setUpdatedBy(entity.getUpdatedBy());
        kb.setUpdateTime(entity.getUpdateTime());
        return kb;
    }
}
