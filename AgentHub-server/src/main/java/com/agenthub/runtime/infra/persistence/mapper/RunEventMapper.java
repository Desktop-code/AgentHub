package com.agenthub.runtime.infra.persistence.mapper;

import com.agenthub.runtime.infra.persistence.entity.RunEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_run_event 映射。所属域：runtime。
 */
@Mapper
public interface RunEventMapper extends BaseMapper<RunEventEntity> {
}
