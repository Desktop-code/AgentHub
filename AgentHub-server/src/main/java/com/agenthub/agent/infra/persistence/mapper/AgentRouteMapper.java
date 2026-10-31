package com.agenthub.agent.infra.persistence.mapper;

import com.agenthub.agent.infra.persistence.entity.AgentRouteEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_route 映射。所属域：agent。
 */
@Mapper
public interface AgentRouteMapper extends BaseMapper<AgentRouteEntity> {
}
