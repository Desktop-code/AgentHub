package com.agenthub.rag.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.rag.domain.model.RagDocument;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.infra.persistence.entity.RagDocumentEntity;
import com.agenthub.rag.infra.persistence.mapper.RagDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 文档仓储实现。所属域：rag 的 infra。Entity 不出本包。
 */
@Repository
public class RagDocumentRepositoryImpl implements RagDocumentRepository {

    private final RagDocumentMapper mapper;

    /**
     * @param mapper rag_document
     */
    public RagDocumentRepositoryImpl(RagDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<RagDocument> page(Long kbId, String keyword, long current, long size) {
        long pageCurrent = Math.max(current, 1);
        long pageSize = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<RagDocumentEntity> wrapper = new LambdaQueryWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getKbId, kbId)
                // 关键字只做标题模糊匹配：正文在 rag_chunk 里，跨表搜索不属于列表查询的职责
                .like(keyword != null && !keyword.isBlank(), RagDocumentEntity::getTitle,
                        keyword == null ? null : keyword.trim())
                .orderByDesc(RagDocumentEntity::getId);
        IPage<RagDocumentEntity> page = mapper.selectPage(new Page<>(pageCurrent, pageSize), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RagDocument> findById(Long id) {
        RagDocumentEntity entity = id == null ? null : mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RagDocument> findByKbAndTitle(Long kbId, String title) {
        if (kbId == null || title == null) {
            return Optional.empty();
        }
        RagDocumentEntity entity = mapper.selectOne(new LambdaQueryWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getKbId, kbId)
                .eq(RagDocumentEntity::getTitle, title)
                .orderByDesc(RagDocumentEntity::getId)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public int countByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return Math.toIntExact(mapper.selectCount(
                new LambdaQueryWrapper<RagDocumentEntity>().eq(RagDocumentEntity::getKbId, kbId)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(RagDocument doc, Long operatorId) {
        RagDocumentEntity entity = new RagDocumentEntity();
        entity.setTenantId(doc.getTenantId() == null ? 0L : doc.getTenantId());
        entity.setKbId(doc.getKbId());
        entity.setTitle(doc.getTitle());
        entity.setSourceUri(doc.getSourceUri());
        entity.setMime(doc.getMime());
        entity.setByteSize(doc.getByteSize());
        entity.setContentHash(doc.getContentHash());
        entity.setChunkCount(doc.getChunkCount() == null ? 0 : doc.getChunkCount());
        entity.setStatus(doc.getStatus());
        entity.setErrorMsg(doc.getErrorMsg());
        entity.setVersion(doc.getVersion() == null ? 1 : doc.getVersion());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * chunkCnt 传 null 时不覆盖已记录的切片数（失败场景不该把上一次的成功计数清零）。
     */
    @Override
    public void updateStatus(Long id, String status, Integer chunkCnt, String errorMsg) {
        LambdaUpdateWrapper<RagDocumentEntity> wrapper = new LambdaUpdateWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getId, id)
                .set(RagDocumentEntity::getStatus, status)
                .set(RagDocumentEntity::getErrorMsg, errorMsg);
        if (chunkCnt != null) {
            wrapper.set(RagDocumentEntity::getChunkCount, chunkCnt);
        }
        mapper.update(null, wrapper);
    }

    /** {@inheritDoc} */
    @Override
    public void updateSourceUri(Long id, String sourceUri) {
        if (id == null) {
            return;
        }
        mapper.update(null, new LambdaUpdateWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getId, id)
                .set(RagDocumentEntity::getSourceUri, sourceUri));
    }

    /** {@inheritDoc} */
    @Override
    public void bumpVersion(Long id) {
        RagDocumentEntity entity = mapper.selectById(id);
        if (entity == null) {
            return;
        }
        int next = entity.getVersion() == null ? 2 : entity.getVersion() + 1;
        mapper.update(null, new LambdaUpdateWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getId, id)
                .set(RagDocumentEntity::getVersion, next));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id) {
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public int logicalDeleteByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getKbId, kbId));
    }

    private RagDocument toDomain(RagDocumentEntity entity) {
        RagDocument doc = new RagDocument();
        doc.setId(entity.getId());
        doc.setTenantId(entity.getTenantId());
        doc.setKbId(entity.getKbId());
        doc.setTitle(entity.getTitle());
        doc.setSourceUri(entity.getSourceUri());
        doc.setMime(entity.getMime());
        doc.setByteSize(entity.getByteSize());
        doc.setContentHash(entity.getContentHash());
        doc.setChunkCount(entity.getChunkCount());
        doc.setStatus(entity.getStatus());
        doc.setErrorMsg(entity.getErrorMsg());
        doc.setVersion(entity.getVersion());
        doc.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        doc.setCreateTime(entity.getCreateTime());
        doc.setUpdateTime(entity.getUpdateTime());
        return doc;
    }
}
