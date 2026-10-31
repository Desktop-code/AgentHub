package com.agenthub.rag.infra.persistence;

import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.infra.persistence.entity.RagChunkEntity;
import com.agenthub.rag.infra.persistence.mapper.RagChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片仓储实现。所属域：rag 的 infra。
 * 逐条 insert 以回填自增主键：Milvus 主键就是 MySQL 主键，拿不到 id 就无法写向量。
 */
@Repository
public class RagChunkRepositoryImpl implements RagChunkRepository {

    private final RagChunkMapper mapper;

    /**
     * @param mapper rag_chunk
     */
    public RagChunkRepositoryImpl(RagChunkMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * 不做批量 SQL 拼接：MyBatis-Plus 的批量插入不回填 id，而这里必须拿到每行主键。
     */
    @Override
    public List<RagChunk> insertBatch(List<RagChunk> chunks, Long operatorId) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RagChunk> out = new ArrayList<>(chunks.size());
        for (RagChunk chunk : chunks) {
            RagChunkEntity entity = new RagChunkEntity();
            entity.setTenantId(chunk.getTenantId() == null ? 0L : chunk.getTenantId());
            entity.setKbId(chunk.getKbId());
            entity.setDocId(chunk.getDocId());
            entity.setChunkIndex(chunk.getChunkIndex());
            entity.setParentChunkId(chunk.getParentChunkId());
            entity.setPartIndex(chunk.getPartIndex());
            entity.setContent(chunk.getContent());
            entity.setTokenEst(chunk.getTokenEst());
            entity.setVersion(chunk.getVersion() == null ? 1 : chunk.getVersion());
            entity.setContentHash(chunk.getContentHash());
            entity.setDeleted(chunk.getDeleted() == null ? 0 : chunk.getDeleted());
            entity.setEffectiveFrom(chunk.getEffectiveFrom() == null ? 0L : chunk.getEffectiveFrom());
            entity.setExpireTime(chunk.getExpireTime() == null ? 0L : chunk.getExpireTime());
            entity.setCreateBy(chunk.getCreateBy() == null ? operatorId : chunk.getCreateBy());
            entity.setOwnerUserId(chunk.getOwnerUserId());
            mapper.insert(entity);
            // 回填主键：Milvus 主键用同一个值，两边才能对齐
            chunk.setId(entity.getId());
            chunk.setMilvusPk(entity.getId());
            if (entity.getMilvusPk() != null) {
                // 首次插入时 milvus_pk 列为空，这里补写一次；失败不影响后续（检索以 id 为准）
                RagChunkEntity patch = new RagChunkEntity();
                patch.setId(entity.getId());
                patch.setMilvusPk(entity.getId());
                mapper.updateById(patch);
            }
            out.add(chunk);
        }
        return out;
    }

    /**
     * {@inheritDoc}
     * 显式过滤 deleted=0：本表未接 {@code @TableLogic}，预览接口不能把软删片带出来。
     */
    @Override
    public List<RagChunk> listByDocId(Long docId) {
        if (docId == null) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<RagChunkEntity>()
                        .eq(RagChunkEntity::getDocId, docId)
                        .eq(RagChunkEntity::getDeleted, 0)
                        .orderByAsc(RagChunkEntity::getChunkIndex))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<RagChunk> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<RagChunkEntity>()
                        .in(RagChunkEntity::getId, ids))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public int deleteByDocId(Long docId) {
        if (docId == null) {
            return 0;
        }
        // 物理删除：deleted 是给检索过滤用的业务标记，重灌时没必要留旧行占空间
        return mapper.delete(new LambdaQueryWrapper<RagChunkEntity>()
                .eq(RagChunkEntity::getDocId, docId));
    }

    /** {@inheritDoc} */
    @Override
    public int countByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return Math.toIntExact(mapper.selectCount(
                new LambdaQueryWrapper<RagChunkEntity>().eq(RagChunkEntity::getKbId, kbId)));
    }

    /** {@inheritDoc} */
    @Override
    public int deleteByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<RagChunkEntity>()
                .eq(RagChunkEntity::getKbId, kbId));
    }

    private RagChunk toDomain(RagChunkEntity entity) {
        RagChunk chunk = new RagChunk();
        chunk.setId(entity.getId());
        chunk.setTenantId(entity.getTenantId());
        chunk.setKbId(entity.getKbId());
        chunk.setDocId(entity.getDocId());
        chunk.setChunkIndex(entity.getChunkIndex());
        chunk.setParentChunkId(entity.getParentChunkId());
        chunk.setPartIndex(entity.getPartIndex());
        chunk.setContent(entity.getContent());
        chunk.setTokenEst(entity.getTokenEst());
        chunk.setMilvusPk(entity.getMilvusPk());
        chunk.setVersion(entity.getVersion());
        chunk.setContentHash(entity.getContentHash());
        chunk.setDeleted(entity.getDeleted());
        chunk.setEffectiveFrom(entity.getEffectiveFrom());
        chunk.setExpireTime(entity.getExpireTime());
        chunk.setCreateBy(entity.getCreateBy());
        chunk.setOwnerUserId(entity.getOwnerUserId());
        return chunk;
    }
}
