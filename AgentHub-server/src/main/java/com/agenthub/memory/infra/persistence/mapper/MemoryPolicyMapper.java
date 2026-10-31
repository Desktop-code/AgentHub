package com.agenthub.memory.infra.persistence.mapper;

import com.agenthub.memory.infra.persistence.entity.MemoryPolicyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * memory_agent_policy 映射。所属域：memory。
 */
@Mapper
public interface MemoryPolicyMapper extends BaseMapper<MemoryPolicyEntity> {
}
