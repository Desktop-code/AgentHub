package com.agenthub.rag.infra.persistence.mapper;

import com.agenthub.rag.infra.persistence.entity.AgentKbBindEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_kb_bind 映射。所属域：rag 的 infra。
 */
@Mapper
public interface AgentKbBindMapper extends BaseMapper<AgentKbBindEntity> {
}
