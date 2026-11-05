package com.agenthub.agent.infra.persistence.mapper;

import com.agenthub.agent.infra.persistence.entity.MaDefEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * ma_def 映射。所属域：agent。
 */
@Mapper
public interface MaDefMapper extends BaseMapper<MaDefEntity> {
}
