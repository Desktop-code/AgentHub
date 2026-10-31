package com.agenthub.agent.infra.persistence.mapper;

import com.agenthub.agent.infra.persistence.entity.AgentModelBindEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_model_bind 映射。所属域：agent。
 */
@Mapper
public interface AgentModelBindMapper extends BaseMapper<AgentModelBindEntity> {
}
