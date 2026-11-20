package com.agenthub.rag.infra.persistence;

import com.agenthub.rag.domain.model.RagDocumentParse;
import com.agenthub.rag.domain.repository.RagDocumentParseRepository;
import com.agenthub.rag.infra.persistence.entity.RagDocumentParseEntity;
import com.agenthub.rag.infra.persistence.mapper.RagDocumentParseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 解析文本产物仓储实现。所属域：rag 的 infra。
 * <p>
 * 唯一键是 doc_id，因此 save 走「先查再插 / 更新」，避免并发下重复行；
 * 入库线程与重切线程对同一文档串行（文档状态闸门保证不会双跑），不需要额外锁。
 */
@Repository
public class RagDocumentParseRepositoryImpl implements RagDocumentParseRepository {

    private final RagDocumentParseMapper mapper;

    /**
     * @param mapper rag_document_parse
     */
    public RagDocumentParseRepositoryImpl(RagDocumentParseMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RagDocumentParse> findByDocId(Long docId) {
        if (docId == null) {
            return Optional.empty();
        }
        RagDocumentParseEntity entity = mapper.selectOne(new LambdaQueryWrapper<RagDocumentParseEntity>()
                .eq(RagDocumentParseEntity::getDocId, docId)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public void save(RagDocumentParse parse) {
        if (parse == null || parse.getDocId() == null) {
            return;
        }
        RagDocumentParseEntity existing = mapper.selectOne(new LambdaQueryWrapper<RagDocumentParseEntity>()
                .eq(RagDocumentParseEntity::getDocId, parse.getDocId())
                .last("LIMIT 1"));
        if (existing == null) {
            RagDocumentParseEntity entity = new RagDocumentParseEntity();
            entity.setTenantId(parse.getTenantId() == null ? 0L : parse.getTenantId());
            entity.setKbId(parse.getKbId());
            entity.setDocId(parse.getDocId());
            entity.setContent(parse.getContent() == null ? "" : parse.getContent());
            entity.setCharCount(parse.getCharCount() == null ? 0 : parse.getCharCount());
            entity.setHeaderCount(parse.getHeaderCount() == null ? 0 : parse.getHeaderCount());
            entity.setDocType(parse.getDocType());
            entity.setStrategy(parse.getStrategy());
            entity.setVersion(parse.getVersion() == null ? 1 : parse.getVersion());
            mapper.insert(entity);
            parse.setId(entity.getId());
            return;
        }
        int nextVersion = parse.getVersion() != null ? parse.getVersion()
                : (existing.getVersion() == null ? 2 : existing.getVersion() + 1);
        mapper.update(null, new LambdaUpdateWrapper<RagDocumentParseEntity>()
                .eq(RagDocumentParseEntity::getDocId, parse.getDocId())
                .set(RagDocumentParseEntity::getContent, parse.getContent() == null ? "" : parse.getContent())
                .set(RagDocumentParseEntity::getCharCount, parse.getCharCount() == null ? 0 : parse.getCharCount())
                .set(RagDocumentParseEntity::getHeaderCount, parse.getHeaderCount() == null ? 0 : parse.getHeaderCount())
                .set(RagDocumentParseEntity::getDocType, parse.getDocType())
                .set(RagDocumentParseEntity::getStrategy, parse.getStrategy())
                .set(RagDocumentParseEntity::getVersion, nextVersion));
        parse.setId(existing.getId());
        parse.setVersion(nextVersion);
    }

    /** {@inheritDoc} */
    @Override
    public void updateStats(Long docId, Integer headerCount, String docType, String strategy) {
        if (docId == null) {
            return;
        }
        mapper.update(null, new LambdaUpdateWrapper<RagDocumentParseEntity>()
                .eq(RagDocumentParseEntity::getDocId, docId)
                .set(headerCount != null, RagDocumentParseEntity::getHeaderCount, headerCount)
                .set(docType != null, RagDocumentParseEntity::getDocType, docType)
                .set(strategy != null, RagDocumentParseEntity::getStrategy, strategy));
    }

    /** {@inheritDoc} */
    @Override
    public int deleteByDocId(Long docId) {
        if (docId == null) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<RagDocumentParseEntity>()
                .eq(RagDocumentParseEntity::getDocId, docId));
    }

    /** {@inheritDoc} */
    @Override
    public int deleteByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        return mapper.delete(new LambdaQueryWrapper<RagDocumentParseEntity>()
                .eq(RagDocumentParseEntity::getKbId, kbId));
    }

    private RagDocumentParse toDomain(RagDocumentParseEntity entity) {
        RagDocumentParse parse = new RagDocumentParse();
        parse.setId(entity.getId());
        parse.setTenantId(entity.getTenantId());
        parse.setKbId(entity.getKbId());
        parse.setDocId(entity.getDocId());
        parse.setContent(entity.getContent());
        parse.setCharCount(entity.getCharCount());
        parse.setHeaderCount(entity.getHeaderCount());
        parse.setDocType(entity.getDocType());
        parse.setStrategy(entity.getStrategy());
        parse.setVersion(entity.getVersion());
        return parse;
    }
}
