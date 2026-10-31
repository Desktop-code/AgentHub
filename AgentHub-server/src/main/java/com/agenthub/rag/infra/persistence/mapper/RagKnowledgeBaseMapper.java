package com.agenthub.rag.infra.persistence.mapper;

import com.agenthub.rag.infra.persistence.entity.RagKnowledgeBaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag_knowledge_base 映射。所属域：rag 的 infra。
 */
@Mapper
public interface RagKnowledgeBaseMapper extends BaseMapper<RagKnowledgeBaseEntity> {
}
