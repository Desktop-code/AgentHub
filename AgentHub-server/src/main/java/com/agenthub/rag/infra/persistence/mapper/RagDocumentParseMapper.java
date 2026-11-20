package com.agenthub.rag.infra.persistence.mapper;

import com.agenthub.rag.infra.persistence.entity.RagDocumentParseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag_document_parse 映射。所属域：rag 的 infra。
 */
@Mapper
public interface RagDocumentParseMapper extends BaseMapper<RagDocumentParseEntity> {
}
