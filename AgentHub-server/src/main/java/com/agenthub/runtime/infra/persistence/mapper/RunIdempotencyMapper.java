package com.agenthub.runtime.infra.persistence.mapper;

import com.agenthub.runtime.infra.persistence.entity.RunIdempotencyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_run_idempotency 映射。所属域：runtime。
 */
@Mapper
public interface RunIdempotencyMapper extends BaseMapper<RunIdempotencyEntity> {
}
