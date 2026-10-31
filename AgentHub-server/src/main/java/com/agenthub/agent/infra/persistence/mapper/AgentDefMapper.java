package com.agenthub.agent.infra.persistence.mapper;

import com.agenthub.agent.infra.persistence.entity.AgentDefEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_def 映射。所属域：agent。
 */
@Mapper
public interface AgentDefMapper extends BaseMapper<AgentDefEntity> {
}
