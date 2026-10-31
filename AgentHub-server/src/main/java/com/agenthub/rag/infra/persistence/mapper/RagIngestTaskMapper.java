package com.agenthub.rag.infra.persistence.mapper;

import com.agenthub.rag.infra.persistence.entity.RagIngestTaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag_ingest_task 映射。所属域：rag 的 infra。
 */
@Mapper
public interface RagIngestTaskMapper extends BaseMapper<RagIngestTaskEntity> {
}
