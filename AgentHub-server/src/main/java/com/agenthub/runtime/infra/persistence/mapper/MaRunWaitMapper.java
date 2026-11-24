package com.agenthub.runtime.infra.persistence.mapper;

import com.agenthub.runtime.infra.persistence.entity.MaRunWaitEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_run_ma_wait 映射。所属域：runtime。
 */
@Mapper
public interface MaRunWaitMapper extends BaseMapper<MaRunWaitEntity> {
}
